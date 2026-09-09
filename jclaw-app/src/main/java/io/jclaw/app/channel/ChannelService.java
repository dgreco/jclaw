package io.jclaw.app.channel;

import io.jclaw.app.observability.ObservedEventLog;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.channel.ChannelAdapter;
import io.jclaw.contracts.channel.ChannelBindingStore;
import io.jclaw.contracts.channel.ReplyTarget;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.secret.SecretInjection;
import io.jclaw.kernel.guard.EgressGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the channel side of the loop: a webhook becomes a turn, and a finished run becomes a reply.
 *
 * <p>Nothing executes in the webhook request. The message is enqueued and the request returns,
 * because a platform expects an answer in seconds and a turn can take minutes, park on a gate, or
 * be resumed by another process. The reply goes out later, driven by the audit log, which is what
 * makes a channel conversation survive gates and restarts.
 *
 * <p>Credentials come from the vault, bound to {@code channel.connect} and the platform's API
 * host. An adapter is handed the value for one call and holds nothing.
 */
public final class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    /** The capability a channel's verify secret and API token must be bound to. */
    public static final CapabilityId CONNECT = CapabilityId.of("channel.connect");

    /** The vault entries one channel needs. */
    public record Credentials(String verifySecret, String token) {
        public Credentials {
            Objects.requireNonNull(verifySecret, "verifySecret");
            Objects.requireNonNull(token, "token");
        }
    }

    private final Map<String, ChannelAdapter> adapters = new LinkedHashMap<>();
    private final Map<String, Credentials> credentials;
    private final ChannelBindingStore bindings;
    private final JclawRuntime runtime;
    private final ThreadService threads;
    private final RunStore runs;
    private final SecretVault vault;
    private final EgressGuard egress;

    public ChannelService(
            java.util.List<ChannelAdapter> adapters, Map<String, Credentials> credentials,
            ChannelBindingStore bindings, JclawRuntime runtime, ThreadService threads, RunStore runs,
            SecretVault vault, EgressGuard egress, EventLog events) {

        Objects.requireNonNull(adapters, "adapters").forEach(adapter -> this.adapters.put(adapter.id(), adapter));
        this.credentials = Map.copyOf(Objects.requireNonNull(credentials, "credentials"));
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.egress = Objects.requireNonNull(egress, "egress");
        if (events instanceof ObservedEventLog observed) {
            observed.addListener(this::onEvent);
        } else if (!this.adapters.isEmpty()) {
            log.debug("channels: event log is not observable; replies will not be delivered");
        }
    }

    /** Whether any channel is configured. */
    public boolean enabled() {
        return !adapters.isEmpty() && !credentials.isEmpty();
    }

    /** Configured channel ids, for {@code doctor}. */
    public Set<String> channels() {
        return adapters.keySet().stream().filter(credentials::containsKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** What a webhook request produced, so the HTTP surface knows what to answer. */
    public sealed interface Handled {
        record Accepted(String run, String thread) implements Handled { }
        record Ignored(String why) implements Handled { }
        record Handshake(String body) implements Handled { }
        record Refused(String reason) implements Handled { }
    }

    /**
     * Verifies and handles one webhook delivery.
     *
     * <p>A refusal is deliberately opaque to the caller: a webhook endpoint that explains exactly
     * why a signature failed is a webhook endpoint that helps someone forge one.
     */
    public Handled receive(String channel, Map<String, String> headers, String body) {
        ChannelAdapter adapter = adapters.get(channel);
        Credentials configured = credentials.get(channel);
        if (adapter == null || configured == null) {
            return new Handled.Refused("no such channel");
        }
        Result<String, String> verifySecret = lease(configured.verifySecret(), adapter);
        if (verifySecret.isErr()) {
            log.debug("channels: {} verify secret unavailable ({})", channel, verifySecret.errorAsOptional().orElse("?"));
            return new Handled.Refused("channel not configured");
        }
        Result<ChannelAdapter.Delivery, String> delivered =
                adapter.receive(headers, body, verifySecret.orElseThrow());
        if (delivered.isErr()) {
            log.debug("channels: {} refused a delivery ({})", channel, delivered.errorAsOptional().orElse("?"));
            return new Handled.Refused("rejected");
        }
        return switch (delivered.orElseThrow()) {
            case ChannelAdapter.Delivery.Handshake handshake -> new Handled.Handshake(handshake.responseBody());
            case ChannelAdapter.Delivery.Ignored ignored -> new Handled.Ignored(ignored.why());
            case ChannelAdapter.Delivery.Message message -> accept(message.inbound());
        };
    }

    private Handled accept(ChannelAdapter.Inbound inbound) {
        ThreadId thread = new ThreadId(inbound.replyTo().threadName());
        bindings.bind(thread, inbound.replyTo());
        var run = runtime.enqueue(thread, ChatMessage.user(inbound.text()));
        log.debug("channels: {} enqueued {} on {} for {}", inbound.replyTo().adapter(),
                run.value(), thread.value(), inbound.author());
        return new Handled.Accepted(run.value(), thread.value());
    }

    /**
     * Sends the reply when a run on a bound thread finishes.
     *
     * <p>Only a completed run produces one. A failure or a cancellation has nothing a person
     * asked for, and a parked run has not finished at all: it is waiting on an approval, and the
     * reply will come when it is resumed.
     */
    void onEvent(JclawEvent event) {
        if (!(event instanceof JclawEvent.RunFinished finished) || finished.status() != TurnStatus.COMPLETED) {
            return;
        }
        Optional<RunStore.RunRecord> record = runs.find(finished.run());
        if (record.isEmpty()) {
            return;
        }
        ThreadId thread = record.get().scope().thread();
        Optional<ReplyTarget> target = bindings.find(thread);
        if (target.isEmpty()) {
            return;
        }
        Optional<String> reply = lastAssistantMessage(thread);
        if (reply.isEmpty()) {
            return;
        }
        deliver(target.get(), reply.get());
    }

    private Optional<String> lastAssistantMessage(ThreadId thread) {
        var history = threads.history(thread, Integer.MAX_VALUE);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i).message();
            if (message.role() == ChatMessage.Role.ASSISTANT && !message.displayText().isBlank()) {
                return Optional.of(message.displayText());
            }
        }
        return Optional.empty();
    }

    /** Sends one message now. Public so a test and the CLI can exercise delivery directly. */
    public Result<String, String> deliver(ReplyTarget target, String text) {
        ChannelAdapter adapter = adapters.get(target.adapter());
        Credentials configured = credentials.get(target.adapter());
        if (adapter == null || configured == null) {
            return Result.err("channel_not_configured");
        }
        Optional<String> url = adapter.sendUrl(target);
        if (url.isPresent()) {
            Result<java.net.URI, String> checked = egress.check(url.get());
            if (checked.isErr()) {
                return Result.err("endpoint_" + checked.errorAsOptional().orElse("denied"));
            }
        }
        Result<String, String> token = lease(configured.token(), adapter);
        if (token.isErr()) {
            return Result.err(token.errorAsOptional().orElse("token_unavailable"));
        }
        Result<String, String> sent = adapter.send(target, text, token.orElseThrow());
        sent.errorAsOptional().ifPresent(reason ->
                log.debug("channels: {} could not deliver to {} ({})",
                        target.adapter(), target.conversation(), reason));
        return sent;
    }

    /** Leases a channel secret, insisting on a binding that names this channel's API host. */
    private Result<String, String> lease(String name, ChannelAdapter adapter) {
        SecretVault.SecretName secret;
        try {
            secret = new SecretVault.SecretName(name);
        } catch (IllegalArgumentException e) {
            return Result.err("secret_name_invalid");
        }
        Optional<SecretVault.Lease> lease = vault.lease(secret);
        if (lease.isEmpty()) {
            return Result.err("secret_unknown: " + name);
        }
        Optional<String> refusal = SecretInjection.refuse(
                lease.get().info().binding(), CONNECT, Set.of(adapter.apiHost()));
        return refusal.<Result<String, String>>map(Result::err)
                .orElseGet(() -> Result.ok(lease.get().value()));
    }
}
