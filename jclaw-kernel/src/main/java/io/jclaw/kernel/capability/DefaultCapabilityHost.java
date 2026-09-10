// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

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
import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.secret.SecretVaults;
import io.jclaw.contracts.turn.TurnRef.LoopGateRef;
import io.jclaw.contracts.turn.TurnRef.LoopResultRef;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.policy.RateLimit;
import io.jclaw.domain.redact.Redaction;
import io.jclaw.domain.safety.InjectionHeuristics;
import io.jclaw.domain.secret.SecretInjection;
import io.jclaw.domain.secret.SecretStaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.HashSet;
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
 *   <li>rate limit — a per-capability cap, checked before a human is bothered with a gate;</li>
 *   <li>approval — auto-approved, matched against an existing exact-invocation grant, or gated;</li>
 *   <li>dispatch to the runtime lane, substituting vault secrets into the arguments the lane
 *       receives when, and only when, each secret's binding allows this capability and every
 *       host the arguments name;</li>
 *   <li>redact, bound, store, and only then mint a result ref.</li>
 * </ol>
 *
 * <p>Secret injection sits inside dispatch on purpose. The fingerprint, the approval prompt, the
 * stored invocation, and every event see the reference form the model wrote; the value exists in
 * the handler's arguments for the duration of one call and is added to the redaction set for
 * that call's output. A lane never holds the vault, so a compromised lane can only use the
 * credential the host chose to give it, against the hosts the binding names.
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
    private final CapabilityPolicyResolver policies;
    private final CapabilityHandler.HandlerContext context;
    private final Supplier<Set<String>> knownSecrets;
    private final SecretVaults vaults;
    private List<LoopHook> loopHooks = List.of();
    private final Clock clock;

    /** Dispatch instants per rate-limited capability; per process, like the limit itself. */
    private final Map<CapabilityId, Deque<Instant>> dispatches = new ConcurrentHashMap<>();

    public DefaultCapabilityHost(
            List<CapabilityHandler> handlers,
            ApprovalStore approvals,
            CapabilityResultStore results,
            EventLog events,
            CapabilityPolicy policy,
            CapabilityHandler.HandlerContext context,
            Supplier<Set<String>> knownSecrets,
            Clock clock) {
        this(handlers, approvals, results, events, CapabilityPolicyResolver.fixed(policy),
                context, knownSecrets, SecretVaults.empty(), clock);
    }

    public DefaultCapabilityHost(
            List<CapabilityHandler> handlers,
            ApprovalStore approvals,
            CapabilityResultStore results,
            EventLog events,
            CapabilityPolicy policy,
            CapabilityHandler.HandlerContext context,
            Supplier<Set<String>> knownSecrets,
            SecretVault vault,
            Clock clock) {
        this(handlers, approvals, results, events, CapabilityPolicyResolver.fixed(policy),
                context, knownSecrets, SecretVaults.shared(vault), clock);
    }

    public DefaultCapabilityHost(
            List<CapabilityHandler> handlers,
            ApprovalStore approvals,
            CapabilityResultStore results,
            EventLog events,
            CapabilityPolicyResolver policies,
            CapabilityHandler.HandlerContext context,
            Supplier<Set<String>> knownSecrets,
            SecretVault vault,
            Clock clock) {
        this(handlers, approvals, results, events, policies, context, knownSecrets,
                SecretVaults.shared(vault), clock);
    }

    /**
     * @param policies the posture per scope, so one host can serve tenants trusted differently
     * @param vaults   the credentials per tenant. The tenant is taken from the invocation's
     *                 scope, never from an argument, so a run cannot name another's vault
     */
    public DefaultCapabilityHost(
            List<CapabilityHandler> handlers,
            ApprovalStore approvals,
            CapabilityResultStore results,
            EventLog events,
            CapabilityPolicyResolver policies,
            CapabilityHandler.HandlerContext context,
            Supplier<Set<String>> knownSecrets,
            SecretVaults vaults,
            Clock clock) {

        Objects.requireNonNull(handlers, "handlers");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.vaults = Objects.requireNonNull(vaults, "vaults");
        this.handlers = index(handlers);
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.results = Objects.requireNonNull(results, "results");
        this.events = Objects.requireNonNull(events, "events");
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
                .filter(descriptor -> !policies.forScope(scope).isDenied(descriptor.id()))
                .sorted(Comparator.comparing(descriptor -> descriptor.id().value()))
                .toList();
    }

    @Override
    public Optional<CapabilityDescriptor> describe(CapabilityId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(handlers.get(id)).map(CapabilityHandler::descriptor);
    }

    /**
     * Hooks consulted when a gate is about to be raised.
     *
     * <p>A setter rather than a constructor parameter because gate hooks are optional and there
     * are four constructors; threading an empty list through all of them would add ceremony to
     * every call site to serve the case that does not use it. Set once at wiring, before any
     * dispatch.
     */
    public void withGateHooks(List<LoopHook> hooks) {
        this.loopHooks = List.copyOf(Objects.requireNonNull(hooks, "hooks"));
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

        CapabilityPolicy policy = policies.forScope(invocation.scope());
        if (policy.isDenied(descriptor.id())) {
            log.debug("capability {}: denied by policy", descriptor.id().value());
            return denied(invocation, descriptor, "capability_denied_by_policy");
        }

        Optional<CapabilityOutcome> limited = evaluateRateLimit(invocation, descriptor);
        if (limited.isPresent()) {
            return limited.get();
        }

        Optional<CapabilityOutcome> gate = evaluateApproval(invocation, descriptor);
        if (gate.isPresent()) {
            return gate.get();
        }

        return dispatch(invocation, descriptor, handler);
    }

    /**
     * Refuses a call that would exceed the capability's rate limit.
     *
     * <p>Checked before approval so a runaway loop of gated calls does not flood a human with
     * gates, and counted at dispatch rather than here so a call that parks on a gate does not
     * spend a permit it never used.
     */
    private Optional<CapabilityOutcome> evaluateRateLimit(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor) {

        RateLimit limit = policies.forScope(invocation.scope()).rateLimits().get(descriptor.id());
        if (limit == null) {
            return Optional.empty();
        }
        Deque<Instant> history = dispatches.computeIfAbsent(descriptor.id(), ignored -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (history) {
            while (!history.isEmpty() && !history.peekFirst().isAfter(now.minus(limit.window()))) {
                history.pollFirst();
            }
            if (!limit.permits(List.copyOf(history), now)) {
                log.debug("capability {}: rate limited ({})", descriptor.id().value(), limit.describe());
                return Optional.of(new CapabilityOutcome.Denied(
                        "rate_limited", Optional.of("limit is " + limit.describe())))
                        .map(outcome -> {
                            emit(invocation, descriptor, "denied", 0);
                            return outcome;
                        });
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a non-empty outcome when the call must not proceed as-is: either it needs a human,
     * or no human is reachable and it is denied.
     */
    private Optional<CapabilityOutcome> evaluateApproval(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor) {

        CapabilityPolicy policy = policies.forScope(invocation.scope());
        if (policy.permitsUnattended(descriptor)) {
            log.debug("capability {}: unattended execution permitted (effect {}, trust {})",
                    descriptor.id().value(), descriptor.effect(), descriptor.trust());
            return Optional.empty();
        }

        String fingerprint = invocation.fingerprint();
        Optional<ApprovalStore.Gate> latest = approvals.findGrant(invocation.scope(), fingerprint);
        if (latest.isPresent()) {
            ApprovalStore.Gate gate = latest.get();
            if (gate.approved().isPresent()) {
                if (gate.isApproved()) {
                    log.debug("capability {}: prior grant matches fingerprint {} -> approved",
                            descriptor.id().value(), fingerprint);
                    return Optional.empty(); // exact invocation already approved
                }
                log.debug("capability {}: prior decision for fingerprint {} is a denial",
                        descriptor.id().value(), fingerprint);
                return Optional.of(denied(invocation, descriptor, "approval_denied"));
            }
            if (!gate.isExpiredAt(clock.instant())) {
                // The same question is still open. Park on it again rather than asking twice:
                // a human who has not answered yet should not find a second copy of the gate.
                log.debug("capability {}: gate {} still pending -> parking on it again",
                        descriptor.id().value(), gate.id().value());
                return Optional.of(new CapabilityOutcome.NeedsApproval(
                        GateKind.APPROVAL, LoopGateRef.of(gate.id()), gate.prompt()));
            }
            // A stale question is not an answer. Ask again with a fresh gate.
            log.debug("capability {}: pending gate {} expired at {} -> raising a fresh one",
                    descriptor.id().value(), gate.id().value(), gate.expiresAt());
        }

        if (!policy.interactive()) {
            // Nobody can answer a gate here, so parking the run would hang it forever.
            log.debug("capability {}: approval required but session is unattended -> denied",
                    descriptor.id().value());
            return Optional.of(denied(invocation, descriptor, "approval_required_but_unattended"));
        }

        // Hooks see the question before a human does. They may amend the prompt — a ticket
        // number, a tenant, a policy note — or refuse to ask it, which becomes a denial. There is
        // deliberately no outcome that approves: only a human or the operator's policy says yes,
        // which is what keeps this class the single authority gate.
        Optional<String> prompt = hookedPrompt(
                invocation, GateKind.APPROVAL, Optional.of(descriptor.id().value()),
                describeForHuman(invocation, descriptor));
        if (prompt.isEmpty()) {
            return Optional.of(denied(invocation, descriptor, "gate_refused_by_hook"));
        }

        ApprovalStore.Gate raised = approvals.raise(
                invocation.run(), invocation.scope(), invocation, prompt.get());
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

        CapabilityPolicy policy = policies.forScope(invocation.scope());
        RateLimit limit = policy.rateLimits().get(descriptor.id());
        if (limit != null) {
            Deque<Instant> history = dispatches.computeIfAbsent(descriptor.id(), ignored -> new ArrayDeque<>());
            synchronized (history) {
                history.addLast(clock.instant());
            }
        }
        // A tool with its own egress allowlist gets a context that applies it after the host's
        // checks; every other tool gets the host context unchanged.
        Set<String> toolHosts = policy.toolEgress().get(descriptor.id());
        CapabilityHandler.HandlerContext scoped =
                toolHosts == null ? context : new ToolScopedContext(context, toolHosts);

        // Vault secrets go into the arguments the lane sees, never into the invocation that is
        // fingerprinted, stored, and described to a human.
        CapabilityInvocation forLane = invocation;
        Set<String> leased = new HashSet<>();
        // The tenant comes from the scope, which was fixed at admission and is not something
        // the model can write. Naming another tenant's vault is therefore not a check that
        // could fail; there is no argument that would express it.
        SecretVault vault = vaults.forTenant(invocation.scope().tenant());
        Set<SecretVault.SecretName> references = SecretInjection.references(invocation.arguments());
        if (!references.isEmpty()) {
            Map<SecretVault.SecretName, String> values = new HashMap<>();
            Set<String> hosts = SecretInjection.urlHosts(invocation.arguments());
            for (SecretVault.SecretName name : references) {
                Optional<SecretVault.Lease> lease = vault.lease(name);
                if (lease.isEmpty()) {
                    log.debug("capability {}: secret {} unknown -> denied", descriptor.id().value(), name.value());
                    return denied(invocation, descriptor, "secret_unknown");
                }
                Optional<String> refusal = SecretInjection.refuse(lease.get().info().binding(), descriptor.id(), hosts);
                if (refusal.isPresent()) {
                    log.debug("capability {}: secret {} refused ({})",
                            descriptor.id().value(), name.value(), refusal.get());
                    return denied(invocation, descriptor, refusal.get());
                }
                values.put(name, lease.get().value());
                leased.add(lease.get().value());
            }
            forLane = new CapabilityInvocation(
                    invocation.capability(), invocation.callId(),
                    SecretInjection.inject(invocation.arguments(), values).orElseThrow(),
                    invocation.scope(), invocation.run());
            for (SecretVault.SecretName name : references) {
                events.append(new JclawEvent.SecretInjected(clock.instant(), invocation.run(), descriptor.id(), name.value()));
            }
        }

        // Staging is the other handoff: a value that goes into a child process's environment and
        // never into an argument, because an argument becomes a command line and a command line
        // is readable by every process on the machine. The names stay in the invocation; only the
        // values move, and only onto the context for this one call.
        Result<Map<String, SecretVault.SecretName>, String> requested =
                SecretStaging.requested(invocation.arguments().get(SecretStaging.ARGUMENT));
        if (requested.isErr()) {
            return denied(invocation, descriptor, requested.errorAsOptional().orElse("secret_env_invalid"));
        }
        Map<String, SecretVault.SecretName> wanted = requested.orElseThrow();
        if (!wanted.isEmpty()) {
            Map<String, String> staged = new LinkedHashMap<>();
            for (Map.Entry<String, SecretVault.SecretName> entry : wanted.entrySet()) {
                Optional<SecretVault.Lease> lease = vault.lease(entry.getValue());
                if (lease.isEmpty()) {
                    log.debug("capability {}: staged secret {} unknown -> denied",
                            descriptor.id().value(), entry.getValue().value());
                    return denied(invocation, descriptor, "secret_unknown");
                }
                Optional<String> refusal = SecretStaging.refuse(lease.get().info().binding(), descriptor.id());
                if (refusal.isPresent()) {
                    log.debug("capability {}: staged secret {} refused ({})",
                            descriptor.id().value(), entry.getValue().value(), refusal.get());
                    return denied(invocation, descriptor, refusal.get());
                }
                staged.put(entry.getKey(), lease.get().value());
                leased.add(lease.get().value());
            }
            scoped = new StagedContext(scoped, staged);
            for (SecretVault.SecretName name : wanted.values()) {
                events.append(new JclawEvent.SecretInjected(
                        clock.instant(), invocation.run(), descriptor.id(), name.value()));
            }
        }

        long startedAt = clock.millis();
        Result<String, HandlerError> executed;
        try {
            executed = handler.execute(forLane, scoped);
        } catch (RuntimeException e) {
            // A lane that throws is a lane bug. It must not take the run down, and its exception
            // text must not escape — it can carry paths, arguments, or credentials.
            log.debug("capability {}: handler threw {} -> failed",
                    descriptor.id().value(), e.getClass().getSimpleName());
            executed = Result.err(HandlerError.failed("handler_threw"));
        }
        long elapsed = clock.millis() - startedAt;

        Set<String> mask = leased;
        return executed.fold(
                payload -> succeed(invocation, descriptor, payload, elapsed, mask),
                // A guard refusal and a broken lane are different events. Preserving the
                // distinction here is what lets an operator grep the audit log for blocked
                // attempts without them hiding among ordinary I/O errors.
                error -> switch (error) {
                    case HandlerError.Denied denied ->
                            denied(invocation, descriptor, denied.reason());
                    case HandlerError.Failed failed ->
                            fail(invocation, descriptor, failed.category(), elapsed);
                    case HandlerError.Waiting waiting ->
                            waitOn(invocation, descriptor, waiting, elapsed);
                });
    }

    /**
     * The lane started something that finishes elsewhere. The run parks on a process gate keyed
     * by the invocation, so a resume that re-dispatches the same call lands on the same gate until
     * the lane reports an outcome.
     */
    /**
     * Runs the gate hooks, returning the prompt to ask or empty when one refused.
     *
     * <p>A hook that throws is treated as no opinion rather than as a refusal. Hooks are host
     * code, but a buggy one turning every gate into a denial would be a worse failure than the
     * bug it came from.
     */
    private Optional<String> hookedPrompt(
            CapabilityInvocation invocation, GateKind kind, Optional<String> capability, String prompt) {
        if (loopHooks.isEmpty()) {
            return Optional.of(prompt);
        }
        // The kernel does not track iteration or budget; a gate hook is asked about a question,
        // not about how far along the run is.
        LoopHook.HookContext context =
                new LoopHook.HookContext(invocation.run(), invocation.scope(), 0, 0.0);
        LoopHook.GateRequest request = new LoopHook.GateRequest(kind, capability, prompt);
        String current = prompt;
        for (LoopHook hook : loopHooks) {
            LoopHook.Outcome<String> outcome;
            try {
                outcome = hook.beforeGate(context,
                        new LoopHook.GateRequest(request.kind(), request.capability(), current));
            } catch (RuntimeException e) {
                log.debug("gate hook {} threw ({}); treating it as no opinion", hook.id(), e.toString());
                continue;
            }
            if (outcome instanceof LoopHook.Outcome.Veto<String> veto) {
                events.append(new JclawEvent.HookFired(
                        clock.instant(), invocation.run(), hook.id(), "before-gate", "vetoed"));
                log.debug("gate hook {} refused to ask ({})", hook.id(), veto.reason());
                return Optional.empty();
            }
            String amended = ((LoopHook.Outcome.Proceed<String>) outcome).value();
            if (!amended.equals(current)) {
                events.append(new JclawEvent.HookFired(
                        clock.instant(), invocation.run(), hook.id(), "before-gate", "amended"));
                current = amended;
            }
        }
        return Optional.of(current);
    }

    private CapabilityOutcome waitOn(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor,
            HandlerError.Waiting waiting, long elapsed) {

        ApprovalStore.Gate gate = approvals.findGrant(invocation.scope(), invocation.fingerprint())
                .filter(existing -> existing.kind() == GateKind.PROCESS)
                .filter(existing -> existing.isPending() && !existing.isExpiredAt(clock.instant()))
                .orElseGet(() -> {
                    // A process gate is the lane saying "not yet", so a hook may reword it but a
                    // refusal here would strand the child work rather than prevent an effect;
                    // the prompt is taken and the veto ignored, which the empty case expresses
                    // by falling back to what the lane asked for.
                    String asked = hookedPrompt(invocation, GateKind.PROCESS,
                            Optional.of(descriptor.id().value()), waiting.prompt())
                            .orElse(waiting.prompt());
                    ApprovalStore.Gate raised = approvals.raiseProcess(
                            invocation.run(), invocation.scope(), invocation, asked);
                    events.append(new JclawEvent.GateRaised(
                            clock.instant(), invocation.run(), GateKind.PROCESS, raised.id().value()));
                    return raised;
                });
        emit(invocation, descriptor, "waiting", elapsed);
        log.debug("capability {}: waiting on process {} (gate {})",
                descriptor.id().value(), waiting.process(), gate.id().value());
        return new CapabilityOutcome.NeedsApproval(GateKind.PROCESS, LoopGateRef.of(gate.id()), gate.prompt());
    }

    private CapabilityOutcome succeed(
            CapabilityInvocation invocation, CapabilityDescriptor descriptor, String payload, long elapsed,
            Set<String> leased) {

        // Redact before anything else sees it, then bound. In this order a secret that straddles
        // the truncation point cannot survive as a fragment. A value leased for this call is
        // masked too, so a server that echoes its Authorization header does not hand the model
        // what the vault withheld.
        Set<String> mask = knownSecrets.get();
        if (!leased.isEmpty()) {
            mask = new HashSet<>(mask);
            mask.addAll(leased);
        }
        String redacted = Redaction.redact(payload, mask);
        boolean truncated = redacted.length() > context.maxOutputBytes();
        String bounded = truncated ? Redaction.bound(redacted, context.maxOutputBytes()) : redacted;

        // The stored payload is the audit record: what the tool actually returned, redacted. What
        // the model sees may differ below.
        LoopResultRef ref = results.store(invocation.run(), invocation, bounded, truncated);

        // Injection heuristics run on the bounded, redacted text, after storage and before the
        // model-facing summary is chosen. The gate above already decided whether the effect may
        // happen; this decides how its output is framed, or whether it is withheld.
        String modelFacing = bounded;
        CapabilityPolicy policy = policies.forScope(invocation.scope());
        if (policy.injection() != InjectionPolicy.OFF) {
            InjectionHeuristics.Assessment assessment = InjectionHeuristics.scan(bounded);
            if (!assessment.clean()) {
                InjectionHeuristics.Severity worst = assessment.highest().orElseThrow();
                boolean block = policy.injection() == InjectionPolicy.BLOCK
                        && worst == InjectionHeuristics.Severity.HIGH;
                String action = block ? "blocked"
                        : policy.injection() == InjectionPolicy.WARN ? "warned" : "sanitized";
                events.append(new JclawEvent.InjectionDetected(
                        clock.instant(), invocation.run(), descriptor.id(), worst.name(),
                        assessment.findings().size(), action));
                log.debug("capability {}: injection heuristics matched {} rule(s), worst {} -> {}",
                        descriptor.id().value(), assessment.findings().size(), worst, action);
                if (block) {
                    emit(invocation, descriptor, "blocked", elapsed);
                    return CapabilityOutcome.Denied.of("injection_suspected",
                            "the tool output contained instruction-like text ("
                                    + String.join(", ", assessment.rules()) + ") and was withheld");
                }
                if (policy.injection() != InjectionPolicy.WARN) {
                    modelFacing = InjectionHeuristics.wrapUntrusted(
                            InjectionHeuristics.neutraliseDelimiters(bounded), assessment);
                }
            }
        }

        emit(invocation, descriptor, "ok", elapsed);
        log.debug("capability {}: ok in {} ms ({} chars{}), result ref {}",
                descriptor.id().value(), elapsed, bounded.length(),
                truncated ? ", truncated" : "", ref.value());
        if (log.isTraceEnabled()) {
            // Already redacted and bounded above — the one form of handler output safe to narrate.
            log.trace("capability {}: output: {}", descriptor.id().value(),
                    Redaction.bound(bounded, 2000).replace("\n", "\\n"));
        }
        return new CapabilityOutcome.Ok(ref, modelFacing, truncated);
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
