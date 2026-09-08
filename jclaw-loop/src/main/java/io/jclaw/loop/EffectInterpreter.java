package io.jclaw.loop;

import io.jclaw.contracts.capability.CapabilityHost;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.CapabilityOutcome;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.loop.CheckpointStore;
import io.jclaw.contracts.loop.FailureKind;
import io.jclaw.contracts.loop.LoopExit;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ContentBlock;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.TurnRef.LoopCheckpointStateRef;
import io.jclaw.contracts.turn.TurnRef.LoopMessageRef;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.loop.LoopDecision;
import io.jclaw.domain.loop.LoopExecutionState;
import io.jclaw.domain.loop.LoopPolicy;
import io.jclaw.domain.loop.LoopStateCodec;
import io.jclaw.domain.loop.Observation;
import io.jclaw.domain.loop.TurnMachine;
import io.jclaw.domain.redact.Redaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The single effect interpreter: the only component that turns a {@link LoopDecision} into a real
 * call.
 *
 * <p>Everything else in the loop is pure. The machine decides, this executes, and the result comes
 * back as an {@link Observation}. Concentrating every side effect in one readable method is what
 * makes the agent's behaviour auditable — there is exactly one place to look for "what can this
 * thing actually do", and it is the switch below.
 *
 * <p>The interpreter is also where the ref-minting discipline is enforced in practice. It never
 * hands the machine a ref it did not obtain from a store, so a {@link LoopExit} assembled from
 * those refs is verifiable by construction.
 *
 * <p>Cancellation is checked between effects only. Interrupting mid-capability could leave a
 * dispatched effect unaccounted for, so the loop stops at the next safe boundary instead.
 */
public final class EffectInterpreter {

    /**
     * Per-run hooks: things that vary by caller rather than by wiring.
     *
     * <p>Bundled into one value because they are all "how this particular run is being driven"
     * rather than dependencies of the interpreter. A CLI run streams to a terminal and renews a
     * lease; a test does neither, and should not have to pass two nulls to say so.
     *
     * @param streamSink when present, model calls stream and deltas are emitted here as they
     *                   arrive; when absent, the interpreter uses the non-streaming path
     */
    public record RunHooks(LeaseHeartbeat heartbeat, Optional<Consumer<ModelProvider.StreamEvent>> streamSink) {

        public RunHooks {
            Objects.requireNonNull(heartbeat, "heartbeat");
            Objects.requireNonNull(streamSink, "streamSink");
        }

        /** No lease to renew, no streaming. The default for tests and simple local runs. */
        public static final RunHooks NONE = new RunHooks(LeaseHeartbeat.NONE, Optional.empty());

        public static RunHooks of(LeaseHeartbeat heartbeat) {
            return new RunHooks(heartbeat, Optional.empty());
        }

        public RunHooks streamingTo(Consumer<ModelProvider.StreamEvent> sink) {
            return new RunHooks(heartbeat, Optional.of(sink));
        }
    }

    /**
     * Renews the run's lease between effects.
     *
     * <p>Returning {@code false} means the lease was lost — recovered by a reconciler, or claimed
     * by another worker. The loop must stop immediately: continuing would mean two workers acting
     * on one run, which is precisely what leases exist to prevent.
     */
    @FunctionalInterface
    public interface LeaseHeartbeat {

        boolean renew();

        /** For callers with no lease to renew, such as tests and single-shot local runs. */
        LeaseHeartbeat NONE = () -> true;
    }


    private static final Logger log = LoggerFactory.getLogger(EffectInterpreter.class);

    /** Cap for payload text in TRACE lines. Logs narrate; the transcript is the record. */
    private static final int TRACE_TEXT_BOUND = 2000;

    /** Loop state schema version, recorded with every checkpoint. */
    private static final int SCHEMA_VERSION = 1;

    /** Backstop against a machine/interpreter cycle that never reaches a decision to finish. */
    private static final int MAX_STEPS = 1000;

    private final ModelProvider provider;
    private final CapabilityHost capabilities;
    private final ThreadService threads;
    private final CheckpointStore checkpoints;
    private final EventLog events;
    private final LoopStateCodec codec;
    private final Clock clock;

    public EffectInterpreter(
            ModelProvider provider,
            CapabilityHost capabilities,
            ThreadService threads,
            CheckpointStore checkpoints,
            EventLog events,
            LoopStateCodec codec,
            Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.events = Objects.requireNonNull(events, "events");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Drives a run to completion.
     *
     * @param cancelled polled between effects; when it flips, the loop stops at the next safe point
     * @return the driver's exit claim, still to be validated by the exit applier
     */
    public LoopExit run(
            TurnRunId run,
            TurnScope scope,
            LoopExecutionState initial,
            LoopPolicy policy,
            AtomicBoolean cancelled) {
        return run(run, scope, initial, policy, cancelled, RunHooks.NONE);
    }

    /** As {@link #run}, with per-run hooks for lease renewal and streaming. */
    public LoopExit run(
            TurnRunId run,
            TurnScope scope,
            LoopExecutionState initial,
            LoopPolicy policy,
            AtomicBoolean cancelled,
            RunHooks hooks) {
        return drive(run, scope, initial, policy, cancelled, new Observation.Start(), hooks);
    }

    /**
     * Continues a run rehydrated from a checkpoint.
     *
     * <p>Identical machinery to {@link #run}; only the opening observation differs. A resumed run
     * is mid-flight, so feeding it {@code Start} would be a protocol violation — which is precisely
     * why the distinction is a separate observation rather than a flag.
     */
    public LoopExit resume(
            TurnRunId run,
            TurnScope scope,
            LoopExecutionState checkpointed,
            LoopPolicy policy,
            AtomicBoolean cancelled) {
        return resume(run, scope, checkpointed, policy, cancelled, RunHooks.NONE);
    }

    /** As {@link #resume}, with per-run hooks for lease renewal and streaming. */
    public LoopExit resume(
            TurnRunId run,
            TurnScope scope,
            LoopExecutionState checkpointed,
            LoopPolicy policy,
            AtomicBoolean cancelled,
            RunHooks hooks) {
        return drive(run, scope,
                checkpointed.withPhase(LoopExecutionState.Phase.RESUMING),
                policy, cancelled, new Observation.Resumed(), hooks);
    }

    private LoopExit drive(
            TurnRunId run,
            TurnScope scope,
            LoopExecutionState initial,
            LoopPolicy policy,
            AtomicBoolean cancelled,
            Observation opening,
            RunHooks hooks) {

        Objects.requireNonNull(hooks, "hooks");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(cancelled, "cancelled");

        LoopExecutionState state = Objects.requireNonNull(initial, "initial");
        Observation observation = opening;

        log.debug("run {}: loop entered with observation={} phase={} ({} messages, budget {} tokens)",
                run.value(), opening.type(), state.phase(), state.messages().size(),
                state.budget().remainingTokens());

        for (int steps = 0; steps < MAX_STEPS; steps++) {
            if (cancelled.get() && !(observation instanceof Observation.CancelRequested)) {
                log.debug("run {}: cancellation observed at a safe point", run.value());
                observation = new Observation.CancelRequested();
            }
            if (!hooks.heartbeat().renew()) {
                // Lost the lease. Stopping here is the safe direction: the alternative is racing
                // whoever now holds it.
                log.debug("run {}: lease lost during execution; stopping", run.value());
                return LoopExit.Failed.of(FailureKind.LEASE_EXPIRED, "lease lost during execution");
            }

            TurnMachine.LoopStep step = TurnMachine.step(state, observation, policy, clock.instant());
            log.debug("run {}: step {} | phase {} + observation {} -> decision {} | next phase {} (iteration {})",
                    run.value(), steps, state.phase(), observation.type(),
                    describe(step.decision()), step.state().phase(), step.state().iteration());
            state = step.state();

            if (step.decision() instanceof LoopDecision.Finish finish) {
                emitFinished(run, state, finish.exit());
                log.debug("run {}: finished after {} steps: {} (spent {} tokens, {} iterations)",
                        run.value(), steps + 1, describe(finish.exit()),
                        state.budget().spent().total(), state.iteration());
                return finish.exit();
            }
            observation = interpret(run, scope, state, step.decision(), hooks);
        }

        // Reaching here means the machine never converged — a defect, not a user-visible condition.
        log.debug("run {}: loop exceeded {} steps without converging", run.value(), MAX_STEPS);
        return LoopExit.Failed.of(FailureKind.INTERNAL, "loop exceeded " + MAX_STEPS + " steps");
    }

    /** The one place an effect happens. */
    private Observation interpret(
            TurnRunId run, TurnScope scope, LoopExecutionState state,
            LoopDecision decision, RunHooks hooks) {

        return switch (decision) {
            case LoopDecision.CallModel call -> callModel(run, call, hooks);
            case LoopDecision.InvokeCapabilities invoke -> invokeCapabilities(run, scope, invoke);
            case LoopDecision.PersistReply persist -> persistReply(scope, persist);
            case LoopDecision.Checkpoint checkpoint -> writeCheckpoint(run, state, checkpoint);
            case LoopDecision.Finish ignored ->
                    throw new IllegalStateException("Finish must be handled by the caller");
        };
    }

    private Observation callModel(TurnRunId run, LoopDecision.CallModel call, RunHooks hooks) {
        log.debug("run {}: calling model {} via provider '{}' ({} messages, {} tools, maxTokens {}, streaming {})",
                run.value(), call.request().model(), provider.id(), call.request().messages().size(),
                call.request().tools().size(), call.request().maxTokens(), hooks.streamSink().isPresent());
        if (log.isTraceEnabled()) {
            log.trace("run {}: system prompt ({} chars): {}", run.value(),
                    call.request().system().length(), boundForTrace(call.request().system()));
            List<ChatMessage> messages = call.request().messages();
            for (int i = 0; i < messages.size(); i++) {
                log.trace("run {}: request message [{}] {}", run.value(), i, describe(messages.get(i)));
            }
        }

        long startedAt = clock.millis();
        // Streaming and non-streaming produce the same ModelResponse, so the machine is unaware
        // of the difference — presentation must not change the agent's behaviour.
        var result = hooks.streamSink()
                .map(sink -> provider.stream(call.request(), sink))
                .orElseGet(() -> provider.complete(call.request()));
        long elapsed = clock.millis() - startedAt;

        return result.fold(
                response -> {
                    events.append(new JclawEvent.ModelCalled(
                            clock.instant(), run, provider.id(), response.modelId(),
                            response.usage(), elapsed));
                    log.debug("run {}: model '{}' replied in {} ms: stop={}, {} tool use(s), "
                                    + "tokens in={} out={} cacheRead={} cacheWrite={}",
                            run.value(), response.modelId(), elapsed, response.stopReason(),
                            response.toolUses().size(), response.usage().inputTokens(),
                            response.usage().outputTokens(), response.usage().cacheReadTokens(),
                            response.usage().cacheWriteTokens());
                    log.trace("run {}: model reply: {}", run.value(), describe(response.message()));
                    return (Observation) new Observation.ModelReplied(response);
                },
                failure -> {
                    log.debug("run {}: model call failed after {} ms: {} (retryable={}, detail={})",
                            run.value(), elapsed, failure.kind(), failure.retryable(),
                            failure.detail().orElse("-"));
                    // The provider's detail is genuinely useful — it explains why a request was
                    // rejected — but it originates outside the host, so it is redacted and bounded
                    // before it is persisted.
                    events.append(new JclawEvent.ModelFailed(
                            clock.instant(), run, provider.id(), failure.kind().name(),
                            failure.detail().map(d -> Redaction.bound(Redaction.redact(d), 200))));
                    return new Observation.ModelFailed(
                            toFailureKind(failure.kind()),
                            failure.retryable(),
                            failure.detail().map(d -> Redaction.bound(Redaction.redact(d), 200)));
                });
    }

    private Observation invokeCapabilities(
            TurnRunId run, TurnScope scope, LoopDecision.InvokeCapabilities invoke) {

        log.debug("run {}: dispatching {} capability call(s)", run.value(), invoke.calls().size());

        List<Observation.CallOutcome> outcomes = new ArrayList<>();
        for (ContentBlock.ToolUse call : invoke.calls()) {
            log.debug("run {}: -> capability {} (callId {})", run.value(), call.name(), call.callId());
            log.trace("run {}: -> arguments: {}", run.value(),
                    boundForTrace(Redaction.redact(String.valueOf(call.input()))));
            CapabilityOutcome outcome = invokeOne(run, scope, call);
            log.debug("run {}: <- capability {} (callId {}): {}",
                    run.value(), call.name(), call.callId(), describe(outcome));
            outcomes.add(new Observation.CallOutcome(call.callId(), outcome));

            if (outcome instanceof CapabilityOutcome.NeedsApproval) {
                // Stop dispatching the rest of the batch: the run is about to park, and running
                // further effects after deciding to block would be exactly the duplicated work
                // checkpointing exists to prevent.
                log.debug("run {}: approval gate raised; remaining {} call(s) not dispatched",
                        run.value(), invoke.calls().size() - outcomes.size());
                break;
            }
        }
        return new Observation.CapabilitiesCompleted(outcomes);
    }

    private CapabilityOutcome invokeOne(TurnRunId run, TurnScope scope, ContentBlock.ToolUse call) {
        CapabilityId id;
        try {
            id = CapabilityId.of(call.name());
        } catch (IllegalArgumentException e) {
            // A model can invent a syntactically invalid tool name. Ordinary, and a denial.
            return CapabilityOutcome.Denied.of("capability_name_invalid", call.name());
        }
        return capabilities.invoke(
                new CapabilityInvocation(id, call.callId(), call.input(), scope, run));
    }

    private Observation persistReply(TurnScope scope, LoopDecision.PersistReply persist) {
        LoopMessageRef ref = threads.appendAssistant(
                scope.thread(), persist.message(), persist.draft());
        log.debug("thread {}: assistant reply persisted ({} chars, draft={}), ref {}",
                scope.thread().value(), persist.message().displayText().length(),
                persist.draft(), ref.value());
        return new Observation.ReplyPersisted(ref);
    }

    private Observation writeCheckpoint(
            TurnRunId run, LoopExecutionState state, LoopDecision.Checkpoint checkpoint) {

        byte[] payload = codec.encode(state);
        LoopCheckpointStateRef ref = checkpoints.write(
                run, checkpoint.kind(), state.iteration(), SCHEMA_VERSION, payload);
        events.append(new JclawEvent.CheckpointWritten(
                clock.instant(), run, checkpoint.kind(), state.iteration()));
        log.debug("run {}: checkpoint {} written at iteration {} ({} bytes), ref {}",
                run.value(), checkpoint.kind(), state.iteration(), payload.length, ref.value());
        return new Observation.Checkpointed(ref, checkpoint.kind());
    }

    private void emitFinished(TurnRunId run, LoopExecutionState state, LoopExit exit) {
        events.append(new JclawEvent.RunFinished(
                clock.instant(),
                run,
                exit.claimedStatus().isTerminal() ? exit.claimedStatus() : io.jclaw.contracts.turn.TurnStatus.COMPLETED,
                exit instanceof LoopExit.Failed failed
                        ? java.util.Optional.of(failed.kind())
                        : java.util.Optional.empty(),
                state.budget().spent(),
                state.iteration()));
    }

    // --- Trace narration. Logs describe; the transcript and event log remain the record. ---

    private static String describe(LoopDecision decision) {
        return switch (decision) {
            case LoopDecision.CallModel ignored -> "call-model";
            case LoopDecision.InvokeCapabilities invoke ->
                    "invoke-capabilities[" + invoke.calls().size() + "]";
            case LoopDecision.PersistReply persist ->
                    persist.draft() ? "persist-reply(draft)" : "persist-reply";
            case LoopDecision.Checkpoint checkpoint -> "checkpoint(" + checkpoint.kind() + ")";
            case LoopDecision.Finish finish -> "finish(" + describe(finish.exit()) + ")";
        };
    }

    private static String describe(LoopExit exit) {
        return switch (exit) {
            case LoopExit.Completed completed ->
                    "completed, " + completed.replyRefs().size() + " reply ref(s), "
                            + completed.resultRefs().size() + " result ref(s)";
            case LoopExit.Blocked blocked ->
                    "blocked on " + blocked.gate() + " gate " + blocked.gateRef().value();
            case LoopExit.Failed failed ->
                    "failed: " + failed.kind() + failed.cause().map(c -> " (" + c + ")").orElse("");
            case LoopExit.Cancelled ignored -> "cancelled";
        };
    }

    private static String describe(CapabilityOutcome outcome) {
        return switch (outcome) {
            case CapabilityOutcome.Ok ok -> "ok (" + ok.summary().length() + " chars"
                    + (ok.truncated() ? ", truncated" : "") + "), ref " + ok.resultRef().value();
            case CapabilityOutcome.Denied denied -> "denied: " + denied.reason();
            case CapabilityOutcome.Failed failed -> "failed: " + failed.category();
            case CapabilityOutcome.NeedsApproval approval ->
                    "needs approval, gate " + approval.gateRef().value();
        };
    }

    /** One message as a TRACE line: role plus its blocks, text redacted and bounded. */
    private static String describe(ChatMessage message) {
        StringBuilder text = new StringBuilder(message.role().name().toLowerCase(java.util.Locale.ROOT));
        text.append(':');
        for (ContentBlock block : message.content()) {
            text.append(' ').append(switch (block) {
                case ContentBlock.Text t -> "text(" + boundForTrace(Redaction.redact(t.text())) + ")";
                case ContentBlock.ToolUse use -> "tool-use(" + use.name() + ", callId " + use.callId()
                        + ", args " + boundForTrace(Redaction.redact(String.valueOf(use.input()))) + ")";
                case ContentBlock.ToolResult result -> "tool-result(callId " + result.callId()
                        + ", error=" + result.isError() + ", "
                        + boundForTrace(Redaction.redact(result.content())) + ")";
                case ContentBlock.Thinking ignored -> "thinking(...)";
            });
        }
        return text.toString();
    }

    /**
     * Bounds payload text for a TRACE line and folds newlines, so one log line stays one line.
     * Redaction happens at the call site — this is presentation only.
     */
    private static String boundForTrace(String text) {
        return Redaction.bound(text, TRACE_TEXT_BOUND).replace("\n", "\\n");
    }

    /** Maps a provider's failure category onto the loop's public failure vocabulary. */
    private static FailureKind toFailureKind(ModelProvider.ProviderFailure.Kind kind) {
        return switch (kind) {
            case AUTH -> FailureKind.POLICY_DENIED;
            case RATE_LIMIT, UPSTREAM, TRANSPORT -> FailureKind.PROVIDER_ERROR;
            case INVALID_REQUEST -> FailureKind.INVALID_REQUEST;
            case EGRESS_DENIED -> FailureKind.POLICY_DENIED;
            case UNKNOWN_MODEL -> FailureKind.PROVIDER_UNAVAILABLE;
        };
    }

}
