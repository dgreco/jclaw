package io.jclaw.app.runtime;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHost;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.LoopExit;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ToolSpec;
import io.jclaw.contracts.model.ModelProvider;
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
import io.jclaw.domain.prompt.PromptAssembly;
import io.jclaw.contracts.skill.SkillCatalog;
import io.jclaw.kernel.guard.WorkspaceGuard;
import io.jclaw.loop.EffectInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final CheckpointStore checkpoints;
    private final RunStore runs;
    private final ThreadLock threadLocks;
    private final LoopStateCodec codec;
    private final EventLog events;
    private final JclawProperties properties;
    private final WorkspaceGuard workspace;
    private final SkillCatalog skills;
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
            CheckpointStore checkpoints,
            RunStore runs,
            ThreadLock threadLocks,
            LoopStateCodec codec,
            EventLog events,
            JclawProperties properties,
            WorkspaceGuard workspace,
            SkillCatalog skills,
            Clock clock) {
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.threadLocks = Objects.requireNonNull(threadLocks, "threadLocks");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.events = Objects.requireNonNull(events, "events");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Objects.requireNonNull(streamSink, "streamSink");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(userText, "userText");
        Objects.requireNonNull(cancelled, "cancelled");

        TurnScope scope = TurnScope.local(projectName(), thread);
        TurnRunId run = TurnRunId.fresh();
        LoopPolicy policy = resolvePolicy(scope, properties.model(), assembleSystemPrompt());

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
        try (ThreadLock.Held ignored = held.get()) {
            return admit(run, scope, thread, userText, policy, cancelled, streamSink);
        }
    }

    /** The admitted path of {@link #submit}: everything after the thread lock is held. */
    private TurnResult admit(
            TurnRunId run,
            TurnScope scope,
            ThreadId thread,
            String userText,
            LoopPolicy policy,
            AtomicBoolean cancelled,
            Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {

        // The inbound message is durable before any run exists, so a crash cannot lose what the
        // user asked for. The run's resolved profile is recorded at the same moment so a resume
        // replays the same model rather than whatever the config says later.
        threads.acceptInbound(thread, ChatMessage.user(userText));
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

        LoopExecutionState initial = LoopExecutionState.start(seedMessages(thread, userText), budget());
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
        if (!record.isResumable()) {
            log.debug("run {}: status {} is not resumable", run.value(), record.status());
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
        try (ThreadLock.Held ignored = held.get()) {
            return resumeHeld(run, record, cancelled);
        }
    }

    /** The rest of {@link #resume}, executed while the thread lock is held. */
    private TurnResult resumeHeld(TurnRunId run, RunStore.RunRecord record, AtomicBoolean cancelled) {
        Optional<CheckpointStore.Checkpoint> checkpoint = checkpoints.latestFor(run);
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
        runs.updateStatus(run, TurnStatus.QUEUED);
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
                } else {
                    log.debug("run {}: reply ref resolved against the transcript ({} chars)",
                            run.value(), reply.get().length());
                }
                yield reply.isEmpty()
                        ? failed(run, FailureKind.DRIVER_PROTOCOL_VIOLATION)
                        : new TurnResult(run, TurnStatus.COMPLETED, reply, Optional.empty(),
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
        return new LoopPolicy(model, systemPrompt, tools, 8192, 2);
    }

    /**
     * Builds the system prompt for a new run.
     *
     * <p>Assembled once at admission and stored on the run, so a resume replays the same prompt
     * rather than picking up a skill installed in the meantime — the model's instructions must not
     * change underneath a conversation it is midway through.
     */
    private String assembleSystemPrompt() {
        return PromptAssembly.systemPrompt(
                properties.systemPrompt(),
                PromptAssembly.workspaceName(workspace.root()),
                skills.list());
    }

    /** Seeds the machine with prior conversation plus the new message. */
    private List<ChatMessage> seedMessages(ThreadId thread, String userText) {
        List<ChatMessage> history = threads.history(thread, 40).stream()
                .map(ThreadService.ThreadMessage::message)
                .toList();
        return history.isEmpty() ? List.of(ChatMessage.user(userText)) : history;
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
        java.nio.file.Path name = workspace.root().getFileName();
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

    /** Capabilities publishable to the model for a scope. Used by {@code jclaw tools}. */
    public List<CapabilityDescriptor> visibleCapabilities() {
        return capabilities.visibleSurface(TurnScope.local("default", new ThreadId("inspect")));
    }
}
