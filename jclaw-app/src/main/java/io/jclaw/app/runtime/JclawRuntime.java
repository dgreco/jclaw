// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHost;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.LoopExit;
import io.jclaw.contracts.loop.LoopHook;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ToolSpec;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.skill.SkillCatalog;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.ThreadLock;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.domain.budget.Budget;
import io.jclaw.domain.loop.LoopExecutionState;
import io.jclaw.domain.loop.LoopPolicy;
import io.jclaw.domain.loop.LoopStateCodec;
import io.jclaw.domain.projection.RunProjection;
import io.jclaw.domain.prompt.ContextCompaction;
import io.jclaw.domain.prompt.ContextPolicy;
import io.jclaw.domain.prompt.PromptAssembly;
import io.jclaw.kernel.guard.WorkspaceGuard;
import io.jclaw.loop.EffectInterpreter;
import io.jclaw.storage.projection.RunProjectionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The product-facing runtime: submit a message, get a reply; or resume a run that parked.
 *
 * <p>The only surface the CLI touches. Commands never reach into the interpreter, the capability
 * host, or a store — that indirection is what lets a second product surface reuse the whole machine
 * without duplicating orchestration.
 *
 * <p>It also owns exit validation. The interpreter returns a {@link LoopExit}, which is a
 * <em>claim</em>; this class re-resolves the refs inside it against the stores that minted them
 * before reporting success. A driver returning {@code Completed} with a ref that does not resolve
 * gets {@link FailureKind#DRIVER_PROTOCOL_VIOLATION}, not a completed turn.
 */
@Service
public class JclawRuntime {

    private static final Logger log = LoggerFactory.getLogger(JclawRuntime.class);

    private final EffectInterpreter interpreter;
    private final ThreadService threads;
    private final CapabilityHost capabilities;
    private final CapabilityResultStore results;
    private final CheckpointStore checkpoints;
    private final RunStore runs;
    private final ThreadLock threadLocks;
    private final LoopStateCodec codec;
    private final EventLog events;
    private final RunProjectionCache projections;
    private final List<LoopHook> loopHooks;
    private final JclawProperties properties;
    private final WorkspaceGuard workspace;
    private final SkillCatalog skills;
    private final SecretVault vault;
    private Optional<TenantLedger> ledger = Optional.empty();
    private final Clock clock;

    /**
     * How long a claim survives without renewal. Long enough that a slow model call does not
     * expire it, short enough that a crashed worker's run is recoverable in a sensible time.
     */
    static final Duration LEASE_TTL = Duration.ofMinutes(2);

    /**
     * Identifies this process for leasing. Regenerated per process on purpose: a restarted worker
     * is a different worker, and must not inherit a claim its predecessor died holding.
     */
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public JclawRuntime(
            EffectInterpreter interpreter,
            ThreadService threads,
            CapabilityHost capabilities,
            CapabilityResultStore results,
            CheckpointStore checkpoints,
            RunStore runs,
            ThreadLock threadLocks,
            LoopStateCodec codec,
            EventLog events,
            JclawProperties properties,
            WorkspaceGuard workspace,
            SkillCatalog skills,
            SecretVault vault,
            RunProjectionCache projections,
            List<LoopHook> loopHooks,
            Clock clock) {
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.results = Objects.requireNonNull(results, "results");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.threadLocks = Objects.requireNonNull(threadLocks, "threadLocks");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.events = Objects.requireNonNull(events, "events");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.loopHooks = List.copyOf(Objects.requireNonNull(loopHooks, "loopHooks"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** How long a claim lasts before another worker may take the run. */
    public Duration leaseTtl() {
        return LEASE_TTL;
    }

    /** Refuses an enqueue for a tenant that has spent its budget. Callers map it to a refusal. */
    public static final class TenantOverBudget extends IllegalStateException {
        public TenantOverBudget(String tenant) {
            super("tenant '" + tenant + "' has spent its token budget");
        }
    }

    /** The outcome of one turn, as the product surface sees it. */
    public record TurnResult(
            TurnRunId run,
            TurnStatus status,
            Optional<String> reply,
            Optional<FailureKind> failure,
            Optional<String> failureDetail,
            Optional<String> gatePrompt,
            long tokensSpent,
            int iterations) {

        public boolean isSuccess() {
            return status == TurnStatus.COMPLETED;
        }
    }

    /**
     * Runs one turn to completion.
     *
     * @param cancelled polled between effects so a user interrupt stops the run at a safe point
     */
    public TurnResult submit(ThreadId thread, String userText, AtomicBoolean cancelled) {
        return submit(thread, userText, cancelled, Optional.empty());
    }

    /**
     * Runs one turn, streaming model output to {@code streamSink} as it arrives.
     *
     * <p>Streaming is presentation only. The same {@code ModelResponse} reaches the machine either
     * way, so a streamed run and a buffered one produce identical decisions and identical
     * transcripts — displaying tokens early must never change what the agent does.
     */
    public TurnResult submit(
            ThreadId thread,
            String userText,
            AtomicBoolean cancelled,
            Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {
        Objects.requireNonNull(userText, "userText");
        return submit(thread, ChatMessage.user(userText), cancelled, streamSink);
    }

    /**
     * Runs one turn whose inbound message may carry attachments alongside its text.
     *
     * <p>The message is the unit of admission: it is written to the transcript whole, so an
     * attached image is as durable as the words around it and reaches the model in the same
     * request.
     */
    public TurnResult submit(
            ThreadId thread,
            ChatMessage inbound,
            AtomicBoolean cancelled,
            Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {
        return submit(LOCAL_TENANT, thread, inbound, cancelled, streamSink);
    }

    /**
     * Runs one turn on behalf of a tenant.
     *
     * <p>The tenant is the isolation key everything scope-aware separates by: memories,
     * approvals, the thread lock, the scheduler's per-tenant cap. The CLI is the {@code local}
     * tenant; the HTTP surface passes the authenticated user.
     */
    public TurnResult submit(
            String tenant,
            ThreadId thread,
            ChatMessage inbound,
            AtomicBoolean cancelled,
            Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {
        Objects.requireNonNull(streamSink, "streamSink");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(cancelled, "cancelled");
        if (inbound.role() != ChatMessage.Role.USER) {
            throw new IllegalArgumentException("an inbound message must have the user role");
        }
        String userText = inbound.displayText();

        TurnScope scope = scopeFor(tenant, thread);
        if (!admits(scope.tenant())) {
            log.debug("tenant {} is over its token budget; refusing admission", scope.tenant());
            return new TurnResult(TurnRunId.fresh(), TurnStatus.FAILED, Optional.empty(),
                    Optional.of(FailureKind.BUDGET_EXHAUSTED), Optional.of("tenant token budget spent"),
                    Optional.empty(), 0, 0);
        }
        TurnRunId run = TurnRunId.fresh();
        LoopPolicy policy;
        try {
            policy = resolvePolicy(scope, properties.modelFor(scope.agent()),
                    assembleSystemPrompt(run, scope));
        } catch (PromptRefused refused) {
            log.debug("run {}: a hook refused the turn at prompt assembly ({})",
                    run.value(), refused.getMessage());
            return new TurnResult(run, TurnStatus.FAILED, Optional.empty(),
                    Optional.of(FailureKind.POLICY_DENIED), Optional.of(refused.getMessage()),
                    Optional.empty(), 0, 0);
        }

        log.debug("run {}: admitted on thread {} (project {}, model {}, {} tools visible, "
                        + "system prompt {} chars)",
                run.value(), thread.value(), scope.project(), policy.model(),
                policy.tools().size(), policy.systemPrompt().length());
        log.trace("run {}: user text ({} chars): {}", run.value(), userText.length(), userText);

        // One active run per thread, decided before anything is written. A refused submission
        // leaves no inbound message and no run record: there is nothing to undo, and the second
        // process simply learns the thread is busy. Two runs interleaving on one thread would
        // corrupt the transcript in a way no later check could repair.
        Optional<ThreadLock.Held> held = threadLocks.tryAcquire(scope);
        if (held.isEmpty()) {
            log.debug("run {}: thread {} is held by another run -> refused at admission",
                    run.value(), thread.value());
            return threadBusy(run, thread);
        }
        ThreadLock.Held lock = held.get();
        try (lock) {
            return admit(run, scope, thread, inbound, policy, cancelled, streamSink);
        }
    }

    /** The admitted path of {@link #submit}: everything after the thread lock is held. */
    private TurnResult admit(
            TurnRunId run,
            TurnScope scope,
            ThreadId thread,
            ChatMessage inbound,
            LoopPolicy policy,
            AtomicBoolean cancelled,
            Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {

        // The inbound message is durable before any run exists, so a crash cannot lose what the
        // user asked for. The run's resolved profile is recorded at the same moment so a resume
        // replays the same model rather than whatever the config says later.
        threads.acceptInbound(thread, inbound);
        runs.record(new RunStore.RunRecord(
                run, scope, TurnStatus.RUNNING, policy.model(), policy.systemPrompt(),
                clock.instant(), Optional.empty(), Optional.empty()));
        events.append(new JclawEvent.TurnSubmitted(clock.instant(), run, scope));

        if (!runs.claim(run, workerId, clock.instant().plus(LEASE_TTL))) {
            // Only possible if something else already holds this fresh run's id, which would be a
            // defect rather than contention. Failing closed beats executing it twice.
            log.debug("run {}: fresh run id already claimed -> failing closed", run.value());
            return failed(run, FailureKind.INTERNAL);
        }
        events.append(new JclawEvent.RunClaimed(
                clock.instant(), run, workerId, clock.instant().plus(LEASE_TTL)));
        log.debug("run {}: claimed by {} (lease {}s)", run.value(), workerId, LEASE_TTL.toSeconds());

        LoopExecutionState initial =
                LoopExecutionState.start(seedMessages(thread, inbound, policy), budget());
        log.debug("run {}: seeded with {} message(s) of history; handing to interpreter",
                run.value(), initial.messages().size());
        LoopExit exit = interpreter.run(run, scope, initial, policy, cancelled, hooks(run, streamSink));
        return validate(run, exit);
    }

    /**
     * Continues a run that parked on a gate.
     *
     * <p>Rehydrates the checkpoint and re-drives the loop. The gated capability is re-dispatched,
     * which means the kernel re-authorizes it — resuming never assumes the approval was granted,
     * it asks again and gets whatever answer the approval store now holds. A denied gate therefore
     * resumes into a denial, not into an effect.
     */
    public TurnResult resume(TurnRunId run, AtomicBoolean cancelled) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(cancelled, "cancelled");

        log.debug("run {}: resume requested", run.value());
        Optional<RunStore.RunRecord> found = runs.find(run);
        if (found.isEmpty()) {
            log.debug("run {}: no run record -> failed", run.value());
            return failed(run, FailureKind.INTERNAL);
        }
        RunStore.RunRecord record = found.get();
        if (!record.isResumable() && record.status() != TurnStatus.QUEUED) {
            log.debug("run {}: status {} is neither parked nor queued", run.value(), record.status());
            return new TurnResult(run, record.status(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        }

        // Resuming executes on the thread just as a fresh submission does, so it takes the same
        // lock. Held for the rest of the method; released whatever the outcome.
        Optional<ThreadLock.Held> held = threadLocks.tryAcquire(record.scope());
        if (held.isEmpty()) {
            log.debug("run {}: thread {} is held by another run -> resume refused",
                    run.value(), record.scope().thread().value());
            return threadBusy(run, record.scope().thread());
        }
        ThreadLock.Held lock = held.get();
        try (lock) {
            return resumeHeld(run, record, cancelled);
        }
    }

    /**
     * The rest of {@link #resume}, executed while the thread lock is held.
     *
     * <p>Two shapes of run arrive here. A parked run, or one requeued by recovery, has a
     * checkpoint and is re-driven from it. A run queued by {@link #enqueue} has none and is
     * started fresh from the transcript, which already holds its inbound message.
     */
    private TurnResult resumeHeld(TurnRunId run, RunStore.RunRecord record, AtomicBoolean cancelled) {
        Optional<CheckpointStore.Checkpoint> checkpoint = checkpoints.latestFor(run);
        if (checkpoint.isEmpty() && record.status() == TurnStatus.QUEUED) {
            return startQueued(run, record, cancelled);
        }
        if (checkpoint.isEmpty()) {
            // Without a checkpoint there is no safe continuation point; restarting could repeat
            // effects, so the run stays failed and the user resubmits explicitly.
            log.debug("run {}: no checkpoint to resume from -> failed", run.value());
            runs.updateStatus(run, TurnStatus.FAILED);
            return failed(run, FailureKind.INTERRUPTED_UNEXPECTEDLY);
        }
        log.debug("run {}: rehydrating checkpoint {} (kind {}, iteration {}, schema v{})",
                run.value(), checkpoint.get().ref().value(), checkpoint.get().kind(),
                checkpoint.get().iteration(), checkpoint.get().schemaVersion());

        LoopExecutionState state;
        try {
            state = codec.decode(checkpoint.get().payload(), checkpoint.get().schemaVersion());
        } catch (RuntimeException e) {
            // A checkpoint this build cannot read must not be guessed at.
            log.debug("run {}: checkpoint undecodable ({}) -> failed",
                    run.value(), e.getClass().getSimpleName());
            runs.updateStatus(run, TurnStatus.FAILED);
            return failed(run, FailureKind.INTERNAL);
        }

        // Replay the profile the run was admitted under, not the current configuration.
        LoopPolicy policy = resolvePolicy(record.scope(), record.model(), record.systemPrompt());
        log.debug("run {}: replaying admitted profile (model {}, {} tools visible)",
                run.value(), policy.model(), policy.tools().size());
        if (record.status() != TurnStatus.QUEUED) {
            runs.updateStatus(run, TurnStatus.QUEUED);
        }
        runs.updateStatus(run, TurnStatus.RUNNING);

        if (!runs.claim(run, workerId, clock.instant().plus(LEASE_TTL))) {
            // Another worker is already resuming this run.
            log.debug("run {}: lease already held elsewhere; not resuming here", run.value());
            return new TurnResult(run, record.status(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        }
        LoopExit exit = interpreter.resume(
                run, record.scope(), state, policy, cancelled, hooks(run, Optional.empty()));
        return validate(run, exit);
    }

    /**
     * Admits a turn without executing it.
     *
     * <p>The inbound message and the run record become durable, in that order, and the run is
     * left {@code QUEUED} with no lease for a scheduler to claim. No thread lock is taken: nothing
     * executes here, and the lock is taken by whichever worker runs it. Two queued runs on one
     * thread execute in submission order, one at a time.
     */
    public TurnRunId enqueue(ThreadId thread, String userText) {
        Objects.requireNonNull(userText, "userText");
        return enqueue(thread, ChatMessage.user(userText));
    }

    /** As {@link #enqueue(ThreadId, String)}, with an inbound message that may carry attachments. */
    public TurnRunId enqueue(ThreadId thread, ChatMessage inbound) {
        return enqueue(LOCAL_TENANT, thread, inbound);
    }

    /** As {@link #enqueue(ThreadId, ChatMessage)}, on behalf of a tenant. */
    public TurnRunId enqueue(String tenant, ThreadId thread, ChatMessage inbound) {
        Objects.requireNonNull(thread, "thread");
        if (!admits(tenant)) {
            throw new TenantOverBudget(tenant);
        }
        Objects.requireNonNull(inbound, "inbound");
        if (inbound.role() != ChatMessage.Role.USER) {
            throw new IllegalArgumentException("an inbound message must have the user role");
        }

        TurnScope scope = scopeFor(tenant, thread);
        TurnRunId run = TurnRunId.fresh();
        // A refusal here happens before the inbound message is written, so an enqueue a hook
        // rejects leaves nothing behind — the caller gets the exception, not a queued run.
        LoopPolicy policy = resolvePolicy(scope, properties.modelFor(scope.agent()),
                assembleSystemPrompt(run, scope));

        threads.acceptInbound(thread, inbound);
        runs.record(new RunStore.RunRecord(
                run, scope, TurnStatus.QUEUED, policy.model(), policy.systemPrompt(),
                clock.instant(), Optional.empty(), Optional.empty()));
        events.append(new JclawEvent.TurnSubmitted(clock.instant(), run, scope));
        log.debug("run {}: queued on thread {} (project {})", run.value(), thread.value(), scope.project());
        return run;
    }

    /**
     * The conversation a queued run should see.
     *
     * <p>Several turns may be queued on one thread before any executes, so the transcript can
     * hold inbound messages that belong to <em>later</em> runs. Those are left out: a run answers
     * the conversation as of its own submission. Replies written since are kept, since they answer
     * earlier turns, and the run's own message is placed last so it is the current turn rather
     * than a message the model has already seemingly answered.
     */
    private List<ChatMessage> conversationAsOf(RunStore.RunRecord record) {
        List<ChatMessage> earlier = new ArrayList<>();
        ThreadService.ThreadMessage own = null;
        for (ThreadService.ThreadMessage message : threads.history(record.scope().thread(), Integer.MAX_VALUE)) {
            if (message.message().role() != ChatMessage.Role.USER) {
                earlier.add(message.message());
                continue;
            }
            if (message.createdAt().isAfter(record.submittedAt())) {
                continue; // a later run's turn
            }
            if (own != null) {
                earlier.add(own.message());
            }
            own = message;
        }
        if (own != null) {
            earlier.add(own.message());
        }
        return List.copyOf(earlier);
    }

    /** Starts a run queued by {@link #enqueue}: fresh state seeded from the transcript. */
    private TurnResult startQueued(TurnRunId run, RunStore.RunRecord record, AtomicBoolean cancelled) {
        LoopPolicy policy = resolvePolicy(record.scope(), record.model(), record.systemPrompt());
        runs.updateStatus(run, TurnStatus.RUNNING);
        if (!runs.claim(run, workerId, clock.instant().plus(LEASE_TTL))) {
            log.debug("run {}: lease already held elsewhere; not starting here", run.value());
            return new TurnResult(run, record.status(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        }
        events.append(new JclawEvent.RunClaimed(
                clock.instant(), run, workerId, clock.instant().plus(LEASE_TTL)));

        List<ChatMessage> history = conversationAsOf(record);
        if (history.isEmpty()) {
            // A queued run whose inbound message vanished has nothing to run.
            log.debug("run {}: queued run has no transcript to seed from -> failed", run.value());
            recordStatus(run, TurnStatus.FAILED);
            runs.releaseLease(run);
            return failed(run, FailureKind.INTERNAL);
        }
        LoopExecutionState initial = LoopExecutionState.start(seedFrom(history, policy), budget());
        log.debug("run {}: queued run started with {} message(s) of history", run.value(),
                initial.messages().size());
        LoopExit exit = interpreter.run(run, record.scope(), initial, policy, cancelled,
                hooks(run, Optional.empty()));
        return validate(run, exit);
    }

    /**
     * Re-resolves an exit's evidence before trusting it, and records the resulting lifecycle
     * transition.
     *
     * <p>The whole trust model rests here. A ref is a claim until a store confirms it names a real
     * record, so a syntactically valid but fabricated ref must not produce a completed turn.
     */
    private TurnResult validate(TurnRunId run, LoopExit exit) {
        log.debug("run {}: validating exit claim {}", run.value(), exit.getClass().getSimpleName());
        TurnResult result = switch (exit) {
            case LoopExit.Completed completed -> {
                Optional<String> reply = resolveReply(completed.replyRefs());
                if (reply.isEmpty()) {
                    log.debug("run {}: reply ref did not resolve -> DRIVER_PROTOCOL_VIOLATION",
                            run.value());
                    yield failed(run, FailureKind.DRIVER_PROTOCOL_VIOLATION);
                }
                // Every result ref is a claim too. The store that minted it is durable, so a ref
                // minted before a park still resolves after a resume in another process; one
                // that does not resolve was never minted by this host.
                long unresolved = completed.resultRefs().stream()
                        .filter(ref -> results.resolve(ref).isEmpty())
                        .count();
                if (unresolved > 0) {
                    log.debug("run {}: {} result ref(s) did not resolve -> DRIVER_PROTOCOL_VIOLATION",
                            run.value(), unresolved);
                    yield failed(run, FailureKind.DRIVER_PROTOCOL_VIOLATION);
                }
                log.debug("run {}: reply ref resolved against the transcript ({} chars), "
                                + "{} result ref(s) resolved against the result store",
                        run.value(), reply.get().length(), completed.resultRefs().size());
                yield new TurnResult(run, TurnStatus.COMPLETED, reply, Optional.empty(),
                        Optional.empty(), Optional.empty(), 0, 0);
            }

            case LoopExit.Blocked blocked -> {
                // The checkpoint must resolve, or the run could not be resumed without repeating
                // effects — in which case parking it would strand the user.
                boolean resolvable = checkpoints.resolve(blocked.checkpointRef()).isPresent();
                log.debug("run {}: parked on {} gate {} (checkpoint {} {})",
                        run.value(), blocked.gate(), blocked.gateRef().value(),
                        blocked.checkpointRef().value(), resolvable ? "resolves" : "DOES NOT resolve");
                yield !resolvable
                        ? failed(run, FailureKind.DRIVER_PROTOCOL_VIOLATION)
                        : new TurnResult(run, blocked.gate().blockedStatus(), Optional.empty(),
                                Optional.empty(), Optional.empty(),
                                Optional.of(blocked.gateRef().value()), 0, 0);
            }

            case LoopExit.Failed failure -> new TurnResult(
                    run, TurnStatus.FAILED, Optional.empty(), Optional.of(failure.kind()),
                    failure.cause(), Optional.empty(), 0, 0);

            case LoopExit.Cancelled ignored -> new TurnResult(
                    run, TurnStatus.CANCELLED, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), 0, 0);
        };

        recordStatus(run, result.status());
        // Release explicitly rather than waiting for expiry: a finished run should not sit in the
        // reconciler's candidate set for two minutes.
        runs.releaseLease(run);
        log.debug("run {}: terminal status {} recorded, lease released", run.value(), result.status());
        return result;
    }

    /** Per-run hooks: lease renewal, plus streaming when the caller wants it. */
    private EffectInterpreter.RunHooks hooks(
            TurnRunId run, Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {

        EffectInterpreter.LeaseHeartbeat heartbeat =
                () -> runs.heartbeat(run, workerId, clock.instant().plus(LEASE_TTL));
        return streamSink
                .map(sink -> EffectInterpreter.RunHooks.of(heartbeat).streamingTo(sink))
                .orElseGet(() -> EffectInterpreter.RunHooks.of(heartbeat));
    }

    /** This process's worker id, shown by {@code jclaw status}. */
    public String workerId() {
        return workerId;
    }

    /** Records the transition, tolerating an illegal one rather than losing the user's reply. */
    private void recordStatus(TurnRunId run, TurnStatus status) {
        try {
            runs.updateStatus(run, status);
        } catch (IllegalStateException e) {
            // The lifecycle guard rejected this transition. That is a defect worth surfacing, but
            // not at the cost of discarding a reply the user is waiting for.
        }
        materialise(run, status);
    }

    /**
     * Stores a finished run's projection, so the first read of it is cheap too.
     *
     * <p>The cache is read-through as well, which covers runs that finished before it existed or
     * in another process. Doing it here in addition means the common case — someone looking at a
     * run just after it ended — never pays for the fold. {@code put} ignores a non-terminal run,
     * so parking is not a special case here.
     */
    private void materialise(TurnRunId run, TurnStatus status) {
        if (!status.isTerminal()) {
            return;
        }
        try {
            projections.put(RunProjection.fold(run,
                    events.readRun(run).stream().map(EventLog.Entry::event).toList()));
        } catch (RuntimeException e) {
            // A projection is a convenience. Failing to store one must never fail a run that
            // has already produced its answer.
            log.debug("run {}: projection not materialised ({})", run.value(), e.toString());
        }
    }

    /** Resolves the last reply ref to its text, or empty when the evidence does not hold up. */
    private Optional<String> resolveReply(List<LoopMessageRef> replyRefs) {
        if (replyRefs.isEmpty()) {
            return Optional.empty();
        }
        return threads.resolve(replyRefs.get(replyRefs.size() - 1))
                .map(message -> message.message().displayText());
    }

    private TurnResult failed(TurnRunId run, FailureKind kind) {
        return new TurnResult(run, TurnStatus.FAILED, Optional.empty(), Optional.of(kind),
                Optional.empty(), Optional.empty(), 0, 0);
    }

    /** Refused at admission: the run id was never recorded, and the detail says what to do. */
    private static TurnResult threadBusy(TurnRunId run, ThreadId thread) {
        return new TurnResult(run, TurnStatus.FAILED, Optional.empty(),
                Optional.of(FailureKind.THREAD_BUSY),
                Optional.of("another run is active on thread '" + thread.value()
                        + "'; wait for it to finish or use a different thread"),
                Optional.empty(), 0, 0);
    }

    /**
     * Builds the run profile.
     *
     * <p>Model and system prompt are passed in so a resume can replay what the run was admitted
     * under. The tool surface is taken from the current registry: visibility is publication
     * metadata, and the kernel authorizes each invocation regardless, so a capability added since
     * admission grants nothing by appearing here.
     */
    private LoopPolicy resolvePolicy(TurnScope scope, String model, String systemPrompt) {
        List<ToolSpec> tools = capabilities.visibleSurface(scope).stream()
                .map(CapabilityDescriptor::toToolSpec)
                .toList();
        return new LoopPolicy(model, systemPrompt, tools, 8192, 2, contextPolicy(), properties.loopFamily());
    }

    /**
     * The context policy from configuration.
     *
     * <p>Not stored on the run: it bounds what the model sees, not what the run may do, so a
     * resume picking up a retuned window is a presentation change, not an authority change. The
     * same reasoning applies to the tool surface above.
     */
    private ContextPolicy contextPolicy() {
        return new ContextPolicy(properties.contextMaxMessages(), properties.contextMaxTokens(),
                properties.contextSummarise(), properties.contextSummaryMaxTokens());
    }

    /**
     * The seed for a fresh run.
     *
     * <p>With summarisation on, the whole history is handed to the machine, which summarises what
     * its window drops before the first model call; the first checkpoint is correspondingly
     * larger, once. With it off, the seed is truncated here and the checkpoint stays bounded.
     */
    private List<ChatMessage> seedFrom(List<ChatMessage> history, LoopPolicy policy) {
        if (policy.context().summarise()) {
            return history;
        }
        ContextCompaction.Compacted seed = ContextCompaction.compact(history, policy.context());
        if (seed.compacted()) {
            log.debug("seed compacted, {} of {} message(s) omitted (~{} tokens kept)",
                    seed.omittedMessages(), history.size(), seed.estimatedTokens());
        }
        return seed.messages();
    }

    /**
     * Builds the system prompt for a new run.
     *
     * <p>Assembled once at admission and stored on the run, so a resume replays the same prompt
     * rather than picking up a skill installed in the meantime — the model's instructions must not
     * change underneath a conversation it is midway through.
     */
    private String assembleSystemPrompt(String agent) {
        return PromptAssembly.systemPrompt(
                properties.systemPromptFor(agent),
                PromptAssembly.workspaceName(workspace.root()),
                skills.list(),
                vault.list());
    }

    /**
     * The assembled prompt after every hook has had a look at it.
     *
     * <p>Once per turn, at admission, so what a hook produces is what gets stored on the run and
     * replayed on resume. A vetoing hook throws {@link PromptRefused}, which the caller turns
     * into a failed turn before anything is written — refusing after the inbound message is
     * durable would leave a message with no run to answer it.
     */
    private String assembleSystemPrompt(TurnRunId run, TurnScope scope) {
        String prompt = assembleSystemPrompt(scope.agent());
        if (loopHooks.isEmpty()) {
            return prompt;
        }
        // Budget spent is zero: nothing has run yet, and a hook that reads it at this stage is
        // asking about a turn that has not started.
        LoopHook.HookContext context = new LoopHook.HookContext(run, scope, 0, 0.0);
        for (LoopHook hook : loopHooks) {
            LoopHook.Outcome<String> outcome = hook.beforePrompt(context, prompt);
            if (outcome instanceof LoopHook.Outcome.Veto<String> veto) {
                events.append(new JclawEvent.HookFired(
                        clock.instant(), run, hook.id(), "before-prompt", "vetoed"));
                throw new PromptRefused(hook.id() + ": " + veto.reason());
            }
            String amended = ((LoopHook.Outcome.Proceed<String>) outcome).value();
            if (!amended.equals(prompt)) {
                events.append(new JclawEvent.HookFired(
                        clock.instant(), run, hook.id(), "before-prompt", "amended"));
                prompt = amended;
            }
        }
        return prompt;
    }

    /** A hook refused the turn while its prompt was being assembled. */
    static final class PromptRefused extends RuntimeException {
        PromptRefused(String reason) {
            super(reason);
        }
    }

    /**
     * Seeds the machine with prior conversation plus the new message, compacted to the policy.
     *
     * <p>The full transcript is read and compacted here rather than sliced by the transcript
     * service, for two reasons. Compaction needs to see what it drops to tell the model how much
     * was omitted, and a slice taken by count alone can open with an assistant message or a tool
     * result, which providers reject. Reading everything costs nothing extra: the JSONL service
     * reads the whole file to serve any window. The compacted seed bounds the checkpoint size, and
     * the machine compacts again on every model call as a run's own tool results accumulate.
     */
    private List<ChatMessage> seedMessages(ThreadId thread, ChatMessage inbound, LoopPolicy policy) {
        List<ChatMessage> history = threads.history(thread, Integer.MAX_VALUE).stream()
                .map(ThreadService.ThreadMessage::message)
                .toList();
        if (history.isEmpty()) {
            return List.of(inbound);
        }
        return seedFrom(history, policy);
    }

    private Budget budget() {
        return Budget.of(
                properties.maxTokens(),
                properties.maxIterations(),
                Duration.ofMinutes(10),
                clock.instant());
    }

    /**
     * Project component of the scope, derived from the workspace directory name.
     *
     * <p>Taken from the guard's resolved absolute root rather than the configured path: the
     * configured value is often relative ({@code .}), whose file name is not a usable identifier.
     */
    private String projectName() {
        Path name = workspace.root().getFileName();
        if (name == null) {
            return "default";
        }
        String candidate = name.toString().trim();
        return candidate.isEmpty() || candidate.equals(".") ? "default" : candidate;
    }

    /**
     * The scope a turn on {@code thread} would run under.
     *
     * <p>Exposed so CLI commands address exactly the scope the agent uses. Building a scope
     * independently in a command is how {@code jclaw memory search} ends up looking in a different
     * project than the one the agent writes to.
     */
    public TurnScope scopeFor(ThreadId thread) {
        return TurnScope.local(projectName(), thread);
    }

    /** The tenant the CLI runs as. */
    public static final String LOCAL_TENANT = TurnScope.local("p", new ThreadId("t")).tenant();

    /**
     * Gives the runtime a token ledger, so a tenant over budget is refused at admission.
     *
     * <p>Set after construction rather than injected, because the ledger reads the event log the
     * runtime writes to and a constructor cycle would be the price of doing it the other way.
     */
    public void withLedger(TenantLedger ledger) {
        this.ledger = Optional.ofNullable(ledger);
    }

    /**
     * Whether this tenant may start another turn.
     *
     * <p>Checked at admission by callers that enqueue, so a refusal costs nothing and leaves no
     * trace, and never mid-run, since stopping a turn halfway spends the tokens and produces
     * nothing.
     */
    public boolean admits(String tenant) {
        return ledger.map(l -> l.permits(tenant)).orElse(true);
    }

    /** The scope a turn on {@code thread} would run under for {@code tenant}. */
    public TurnScope scopeFor(String tenant, ThreadId thread) {
        Objects.requireNonNull(tenant, "tenant");
        // The agent is part of the isolation key and part of the run's recorded profile, so a
        // resume replays the configuration the turn was admitted under.
        return new TurnScope(tenant, properties.agentFor(tenant), projectName(), thread);
    }

    /** Capabilities publishable to the model for a scope. Used by {@code jclaw tools}. */
    public List<CapabilityDescriptor> visibleCapabilities() {
        return capabilities.visibleSurface(TurnScope.local("default", new ThreadId("inspect")));
    }
}
