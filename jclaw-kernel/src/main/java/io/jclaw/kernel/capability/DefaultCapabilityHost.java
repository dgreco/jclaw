package io.jclaw.kernel.capability;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityHost;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.CapabilityOutcome;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.GateKind;
import io.jclaw.contracts.turn.TurnRef.LoopGateRef;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.redact.Redaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The kernel's authority gate: the one place a capability effect is decided and dispatched.
 *
 * <p>The decision path runs in a fixed order, and the order is itself a security property —
 * cheapest and most absolute checks first, so a denied call never reaches code that could have a
 * side effect:
 *
 * <ol>
 *   <li>existence — an unknown capability is denied, never dispatched;</li>
 *   <li>hard denial by policy;</li>
 *   <li>approval — auto-approved, matched against an existing exact-invocation grant, or gated;</li>
 *   <li>dispatch to the runtime lane;</li>
 *   <li>redact, bound, store, and only then mint a result ref.</li>
 * </ol>
 *
 * <p>Step 5 is not an afterthought. Handler output is untrusted: it may contain a key a subprocess
 * printed, or a host path. It is redacted before it is stored, before it is summarized for the
 * model, and before it reaches an event — so no downstream consumer has to remember to do it.
 *
 * <p>Every branch returns a {@link CapabilityOutcome}. Nothing here throws for an expected
 * condition, because a denial that arrives as an exception is a denial some caller will forget to
 * handle.
 */
public final class DefaultCapabilityHost implements CapabilityHost {

    private static final Logger log = LoggerFactory.getLogger(DefaultCapabilityHost.class);

    private final Map<CapabilityId, CapabilityHandler> handlers;
    private final ApprovalStore approvals;
    private final CapabilityResultStore results;
    private final EventLog events;
    private final CapabilityPolicy policy;
    private final CapabilityHandler.HandlerContext context;
    private final Supplier<Set<String>> knownSecrets;
    private final Clock clock;

    public DefaultCapabilityHost(
            List<CapabilityHandler> handlers,
            ApprovalStore approvals,
            CapabilityResultStore results,
            EventLog events,
            CapabilityPolicy policy,
            CapabilityHandler.HandlerContext context,
            Supplier<Set<String>> knownSecrets,
            Clock clock) {

        Objects.requireNonNull(handlers, "handlers");
        this.handlers = index(handlers);
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.results = Objects.requireNonNull(results, "results");
        this.events = Objects.requireNonNull(events, "events");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.context = Objects.requireNonNull(context, "context");
        this.knownSecrets = Objects.requireNonNull(knownSecrets, "knownSecrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static Map<CapabilityId, CapabilityHandler> index(List<CapabilityHandler> handlers) {
        Map<CapabilityId, CapabilityHandler> byId = new LinkedHashMap<>();
        for (CapabilityHandler handler : handlers) {
            CapabilityId id = handler.descriptor().id();
            CapabilityHandler previous = byId.put(id, handler);
            if (previous != null) {
                // Silently shadowing a built-in would be a privilege-escalation vector.
                throw new IllegalArgumentException("duplicate capability handler for " + id);
            }
        }
        return Map.copyOf(byId);
    }

    @Override
    public List<CapabilityDescriptor> visibleSurface(TurnScope scope) {
        Objects.requireNonNull(scope, "scope");
        return handlers.values().stream()
                .map(CapabilityHandler::descriptor)
                .filter(descriptor -> !policy.isDenied(descriptor.id()))
                .sorted(Comparator.comparing(descriptor -> descriptor.id().value()))
                .toList();
    }

    @Override
    public Optional<CapabilityDescriptor> describe(CapabilityId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(handlers.get(id)).map(CapabilityHandler::descriptor);
    }

    @Override
    public CapabilityOutcome invoke(CapabilityInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");

        log.debug("capability {}: authorizing (run {}, callId {}, fingerprint {})",
                invocation.capability().value(), invocation.run().value(),
                invocation.callId(), invocation.fingerprint());

        CapabilityHandler handler = handlers.get(invocation.capability());
        if (handler == null) {
            // A model can hallucinate a tool name. That is ordinary, and it is a denial.
            log.debug("capability {}: unknown -> denied", invocation.capability().value());
            return CapabilityOutcome.Denied.of("capability_unknown", invocation.capability().value());
        }
        CapabilityDescriptor descriptor = handler.descriptor();

        if (policy.isDenied(descriptor.id())) {
            log.debug("capability {}: denied by policy", descriptor.id().value());
            return denied(invocation, descriptor, "capability_denied_by_policy");
        }

        Optional<CapabilityOutcome> gate = evaluateApproval(invocation, descriptor);
        if (gate.isPresent()) {
            return gate.get();
        }

        return dispatch(invocation, descriptor, handler);
    }

    /**
     * Returns a non-empty outcome when the call must not proceed as-is: either it needs a human,
     * or no human is reachable and it is denied.
     */
    private Optional<CapabilityOutcome> evaluateApproval(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor) {

        if (policy.permitsUnattended(descriptor)) {
            log.debug("capability {}: unattended execution permitted (effect {}, trust {})",
                    descriptor.id().value(), descriptor.effect(), descriptor.trust());
            return Optional.empty();
        }

        String fingerprint = invocation.fingerprint();
        Optional<ApprovalStore.Gate> existing = approvals.findGrant(invocation.scope(), fingerprint);
        if (existing.isPresent()) {
            ApprovalStore.Gate grant = existing.get();
            if (grant.isApproved()) {
                log.debug("capability {}: prior grant matches fingerprint {} -> approved",
                        descriptor.id().value(), fingerprint);
                return Optional.empty(); // exact invocation already approved
            }
            log.debug("capability {}: prior decision for fingerprint {} is a denial",
                    descriptor.id().value(), fingerprint);
            return Optional.of(denied(invocation, descriptor, "approval_denied"));
        }

        if (!policy.interactive()) {
            // Nobody can answer a gate here, so parking the run would hang it forever.
            log.debug("capability {}: approval required but session is unattended -> denied",
                    descriptor.id().value());
            return Optional.of(denied(invocation, descriptor, "approval_required_but_unattended"));
        }

        ApprovalStore.Gate raised = approvals.raise(
                invocation.run(), invocation.scope(), invocation, describeForHuman(invocation, descriptor));
        events.append(new JclawEvent.GateRaised(
                clock.instant(), invocation.run(), GateKind.APPROVAL, raised.id().value()));
        log.debug("capability {}: approval gate {} raised for fingerprint {}",
                descriptor.id().value(), raised.id().value(), fingerprint);

        return Optional.of(new CapabilityOutcome.NeedsApproval(
                GateKind.APPROVAL,
                LoopGateRef.of(raised.id()),
                raised.prompt()));
    }

    private CapabilityOutcome dispatch(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor, CapabilityHandler handler) {

        log.debug("capability {}: dispatching to {}",
                descriptor.id().value(), handler.getClass().getSimpleName());

        long startedAt = clock.millis();
        Result<String, HandlerError> executed;
        try {
            executed = handler.execute(invocation, context);
        } catch (RuntimeException e) {
            // A lane that throws is a lane bug. It must not take the run down, and its exception
            // text must not escape — it can carry paths, arguments, or credentials.
            log.debug("capability {}: handler threw {} -> failed",
                    descriptor.id().value(), e.getClass().getSimpleName());
            executed = Result.err(HandlerError.failed("handler_threw"));
        }
        long elapsed = clock.millis() - startedAt;

        return executed.fold(
                payload -> succeed(invocation, descriptor, payload, elapsed),
                // A guard refusal and a broken lane are different events. Preserving the
                // distinction here is what lets an operator grep the audit log for blocked
                // attempts without them hiding among ordinary I/O errors.
                error -> switch (error) {
                    case HandlerError.Denied denied ->
                            denied(invocation, descriptor, denied.reason());
                    case HandlerError.Failed failed ->
                            fail(invocation, descriptor, failed.category(), elapsed);
                });
    }

    private CapabilityOutcome succeed(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor, String payload, long elapsed) {

        // Redact before anything else sees it, then bound. In this order a secret that straddles
        // the truncation point cannot survive as a fragment.
        String redacted = Redaction.redact(payload, knownSecrets.get());
        boolean truncated = redacted.length() > context.maxOutputBytes();
        String bounded = truncated ? Redaction.bound(redacted, context.maxOutputBytes()) : redacted;

        LoopResultRef ref = results.store(invocation.run(), invocation, bounded, truncated);
        emit(invocation, descriptor, "ok", elapsed);
        log.debug("capability {}: ok in {} ms ({} chars{}), result ref {}",
                descriptor.id().value(), elapsed, bounded.length(),
                truncated ? ", truncated" : "", ref.value());
        if (log.isTraceEnabled()) {
            // Already redacted and bounded above — the one form of handler output safe to narrate.
            log.trace("capability {}: output: {}", descriptor.id().value(),
                    Redaction.bound(bounded, 2000).replace("\n", "\\n"));
        }
        return new CapabilityOutcome.Ok(ref, bounded, truncated);
    }

    private CapabilityOutcome fail(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor, String category, long elapsed) {
        emit(invocation, descriptor, "failed", elapsed);
        log.debug("capability {}: failed in {} ms (category {})",
                descriptor.id().value(), elapsed, category);
        return CapabilityOutcome.Failed.of(category);
    }

    private CapabilityOutcome denied(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor, String reason) {
        emit(invocation, descriptor, "denied", 0);
        return CapabilityOutcome.Denied.of(reason);
    }

    private void emit(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor, String outcome, long elapsed) {
        events.append(new JclawEvent.CapabilityInvoked(
                clock.instant(),
                invocation.run(),
                descriptor.id(),
                descriptor.effect(),
                invocation.fingerprint(),
                outcome,
                elapsed));
    }

    /**
     * The text a human is shown when approving.
     *
     * <p>Arguments are included because approving blind is not approving — but they are redacted
     * and bounded first, since a tool argument can itself contain a credential.
     */
    private String describeForHuman(CapabilityInvocation invocation, CapabilityDescriptor descriptor) {
        String arguments = Redaction.bound(
                Redaction.redact(invocation.arguments().toString(), knownSecrets.get()), 300);
        return descriptor.id().value() + " " + arguments;
    }

    /** How long a lane may run before the caller should consider it hung. Advisory. */
    public static Duration defaultHandlerTimeout() {
        return Duration.ofSeconds(120);
    }
}
