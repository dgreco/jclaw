package io.jclaw.app.runtime;

import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.domain.secret.SecretLeakScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stops a credential from reaching the model, whichever way it got into the transcript.
 *
 * <p>The vault's binding rules govern where a secret goes. They cannot govern what comes back. A
 * tool reads a config file with a token in it; a server echoes an {@code Authorization} header in
 * an error; a shell command prints its own environment. The value is then just text in a tool
 * result, and the next request carries it to a third party. By that point the capability boundary
 * is several steps behind and every check it performs has already passed.
 *
 * <p>So the check is here, at the last moment before the request leaves: scan the assembled
 * request against the values the vault holds, and rewrite each hit back into its
 * {@code {{secret:NAME}}} reference. Redacting rather than vetoing is the right default — a run
 * that trips this has usually done nothing wrong, and the reference form is both true and exactly
 * what the model would write to use the secret deliberately. What it must never do is send the
 * value.
 *
 * <p>This hook is the one place in the process that reads every secret's value on a hot path,
 * which is the cost of the check and worth stating plainly. It holds nothing: values are leased
 * per request and dropped, and the hook reports names, never values, to the log.
 *
 * <p>Off unless {@code jclaw.hooks} names it. Scanning every outbound request against every
 * secret is not free, and an operator with no vault should not pay for it.
 */
public final class SecretLeakHook implements LoopHook {

    private static final Logger log = LoggerFactory.getLogger(SecretLeakHook.class);

    public static final String ID = "secret-leak-scan";

    private final SecretVault vault;

    public SecretLeakHook(SecretVault vault) {
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Outcome<ModelRequest> beforeModel(HookContext context, ModelRequest request) {
        Objects.requireNonNull(request, "request");
        Map<SecretVault.SecretName, String> values = values();
        if (values.isEmpty()) {
            return Outcome.proceed(request);
        }

        // Scan once over everything that will be sent, so a value split across the system prompt
        // and a message is still caught in whichever part contains it whole.
        StringBuilder outbound = new StringBuilder(request.system());
        for (ChatMessage message : request.messages()) {
            for (ContentBlock block : message.content()) {
                appendText(outbound, block);
            }
        }
        Set<SecretVault.SecretName> found = SecretLeakScan.leaks(outbound.toString(), values);
        if (found.isEmpty()) {
            return Outcome.proceed(request);
        }

        // A Thinking block is signed by the provider, so it round-trips or it does not go at all:
        // rewriting its text would invalidate the signature, and dropping the block would change
        // a request the provider requires to be intact. Neither is a redaction, so the only
        // honest answer is to refuse the call. It is the rare case — reasoning is generated from
        // context that was itself scanned on the way in — and a stopped run is recoverable in a
        // way a sent credential is not.
        for (ChatMessage message : request.messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.Thinking thinking
                        && !SecretLeakScan.leaks(thinking.text(), values).isEmpty()) {
                    log.warn("secret leak scan: a credential is inside signed reasoning; refusing the call");
                    return Outcome.veto("a secret appears in signed provider reasoning, "
                            + "which cannot be redacted without invalidating it");
                }
            }
        }

        log.warn("secret leak scan: redacting {} before the model call",
                found.stream().map(SecretVault.SecretName::value).toList());

        return Outcome.proceed(new ModelRequest(
                request.model(),
                SecretLeakScan.redact(request.system(), values),
                redactMessages(request.messages(), values),
                request.tools(),
                request.maxTokens(),
                request.temperature()));
    }

    private static void appendText(StringBuilder out, ContentBlock block) {
        switch (block) {
            case ContentBlock.Text text -> out.append('\n').append(text.text());
            case ContentBlock.ToolResult result -> out.append('\n').append(result.content());
            // Tool input is where a secret reference would legitimately appear unexpanded; the
            // expanded value would only be here if a lane echoed it back into a later call.
            case ContentBlock.ToolUse use -> out.append('\n').append(use.input());
            case ContentBlock.Thinking thinking -> out.append('\n').append(thinking.text());
            case ContentBlock.Image ignored -> {
                // Base64 image bytes cannot contain a credential the scan would recognise, and
                // walking megabytes of them on every request would cost more than it finds.
            }
        }
    }

    private static List<ChatMessage> redactMessages(
            List<ChatMessage> messages, Map<SecretVault.SecretName, String> values) {
        List<ChatMessage> rewritten = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            List<ContentBlock> blocks = new ArrayList<>(message.content().size());
            for (ContentBlock block : message.content()) {
                blocks.add(redactBlock(block, values));
            }
            rewritten.add(new ChatMessage(message.role(), blocks));
        }
        return rewritten;
    }

    private static ContentBlock redactBlock(
            ContentBlock block, Map<SecretVault.SecretName, String> values) {
        return switch (block) {
            case ContentBlock.Text text -> new ContentBlock.Text(SecretLeakScan.redact(text.text(), values));
            case ContentBlock.ToolResult result -> new ContentBlock.ToolResult(
                    result.callId(), SecretLeakScan.redact(result.content(), values), result.isError());
            case ContentBlock.ToolUse use -> new ContentBlock.ToolUse(
                    use.callId(), use.name(), redactArguments(use.input(), values));
            case ContentBlock.Image image -> image;
            // Unreachable: a Thinking block carrying a secret vetoed the request above. Left
            // as an identity rather than a throw, because a hook must never be the thing that
            // takes a run down.
            case ContentBlock.Thinking thinking -> thinking;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redactArguments(
            Map<String, Object> input, Map<SecretVault.SecretName, String> values) {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        input.forEach((key, value) -> rebuilt.put(key, switch (value) {
            case String text -> SecretLeakScan.redact(text, values);
            case Map<?, ?> nested -> redactArguments((Map<String, Object>) nested, values);
            case List<?> list -> list.stream()
                    .map(item -> item instanceof String text ? SecretLeakScan.redact(text, values) : item)
                    .toList();
            case null, default -> value;
        }));
        return rebuilt;
    }

    /** Every secret's value, leased for this one request. */
    private Map<SecretVault.SecretName, String> values() {
        Map<SecretVault.SecretName, String> values = new LinkedHashMap<>();
        for (SecretVault.SecretInfo info : vault.list()) {
            vault.lease(info.name()).ifPresent(lease -> values.put(info.name(), lease.value()));
        }
        return values;
    }
}
