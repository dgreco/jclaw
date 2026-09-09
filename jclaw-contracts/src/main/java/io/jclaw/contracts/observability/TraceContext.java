package io.jclaw.contracts.observability;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * W3C trace context: what an outbound call carries so a collector can join jclaw's spans to the
 * ones its callee produces.
 *
 * <p>jclaw's traces are folded from the event log, which is exact and costs nothing while a run
 * is happening — but it only ever describes what jclaw itself did. A model provider, an MCP
 * server, or a webhook target has its own tracing, and the two are separate systems unless the
 * request carries the identifiers that link them. That is all this is: the sixteen bytes and
 * eight bytes that {@code traceparent} needs, in the format
 * <a href="https://www.w3.org/TR/trace-context/">the specification</a> defines.
 *
 * <h2>Why this is ambient</h2>
 *
 * <p>{@link #current()} reads a thread-local, which is state where this codebase otherwise passes
 * values. It is deliberate, and it is what OpenTelemetry does for the same reason: the alternative
 * is a parameter on {@code ModelProvider.complete}, on every MCP call, and on every tool, threaded
 * through every implementation and every test — to carry something none of them act on except at
 * the moment bytes leave the process. A header is exactly the kind of cross-cutting concern that
 * a parameter makes worse.
 *
 * <p>The scope is bounded and explicit: {@link #open} returns something to close, the interpreter
 * opens one around a call and closes it after, and nothing reads the context except code writing
 * a header. A thread with no scope open has no context, which is the correct answer for a
 * background sweep or a worker between runs. A child run on another thread does not inherit one,
 * which is also correct — a subagent is its own trace.
 */
public record TraceContext(String traceId, String spanId, boolean sampled) {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    /** Not inheritable: a child run is its own trace, not a continuation of its parent's span. */
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    public TraceContext {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
        if (!TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("a trace id is 32 lower-case hex characters");
        }
        if (!SPAN_ID.matcher(spanId).matches()) {
            throw new IllegalArgumentException("a span id is 16 lower-case hex characters");
        }
    }

    /** The {@code traceparent} header value: version 00, the ids, and the sampled flag. */
    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }

    /** Parses a {@code traceparent} header, or empty when it is not one this version understands. */
    public static Optional<TraceContext> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String[] parts = header.trim().split("-");
        if (parts.length != 4 || !parts[0].equals("00")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TraceContext(parts[1], parts[2], parts[3].endsWith("1")));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The context for the current thread, if a scope is open. */
    public static Optional<TraceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * Makes {@code context} current until the returned scope is closed.
     *
     * <p>Restores whatever was current before rather than clearing, so nesting works and a scope
     * closed out of order cannot strand the thread with someone else's trace.
     */
    public static Scope open(TraceContext context) {
        Objects.requireNonNull(context, "context");
        TraceContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** An open scope. Closing it restores the previous context; it never throws. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
