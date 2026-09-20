// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.model;

import io.jclaw.ports.Result;
import io.jclaw.ports.model.ModelExchange.ModelRequest;
import io.jclaw.ports.model.ModelExchange.ModelResponse;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Port to a model backend. The only place the outbound model wire exists.
 *
 * <p>Failures are returned, not thrown: a provider being down is an expected operating condition
 * the failover chain and the loop's retry budget both need to reason about, and an exception would
 * make that control flow invisible. Implementations must not leak raw provider errors, request
 * bodies, or credentials into {@link ProviderFailure} — the sanitizing happens here, at the
 * boundary, so nothing above ever has to remember to do it.
 */
public interface ModelProvider {

    /** Stable provider id, e.g. {@code anthropic}, {@code openai}, {@code ollama}, {@code mock}. */
    String id();

    /** Whether this provider can serve the given model id. Drives failover-chain selection. */
    boolean supports(String model);

    /** Performs a complete (non-streaming) exchange. */
    Result<ModelResponse, ProviderFailure> complete(ModelRequest request);

    /**
     * Streams a response, emitting deltas to {@code sink} as they arrive, and returning the
     * assembled final response.
     *
     * <p>The default implementation degrades to {@link #complete} and emits the result as a single
     * chunk, so a provider without streaming support is still usable everywhere streaming is
     * requested.
     */
    default Result<ModelResponse, ProviderFailure> stream(ModelRequest request, Consumer<StreamEvent> sink) {
        Objects.requireNonNull(sink, "sink");
        Result<ModelResponse, ProviderFailure> result = complete(request);
        if (result instanceof Result.Ok<ModelResponse, ProviderFailure> ok) {
            String text = ok.value().text();
            if (!text.isEmpty()) {
                sink.accept(new StreamEvent.TextDelta(text));
            }
            sink.accept(new StreamEvent.Completed(ok.value()));
        }
        return result;
    }

    /**
     * A sanitized provider failure.
     *
     * @param kind      stable category
     * @param detail    short bounded hint safe to surface; never a response body or stack trace
     * @param retryable whether trying another provider or the same one later could succeed
     */
    record ProviderFailure(Kind kind, Optional<String> detail, boolean retryable) {

        private static final int MAX_DETAIL = 160;

        /** Coarse provider failure categories. */
        public enum Kind {
            /** Credentials missing, invalid, or expired. */
            AUTH,
            /** Rate limited or quota exceeded. */
            RATE_LIMIT,
            /** Request rejected as malformed or too large. */
            INVALID_REQUEST,
            /** Provider returned 5xx or an unparseable body. */
            UPSTREAM,
            /** Network failure, DNS failure, or timeout. */
            TRANSPORT,
            /** Blocked by the egress guard before leaving the host. */
            EGRESS_DENIED,
            /** Requested model is unknown to this provider. */
            UNKNOWN_MODEL
        }

        public ProviderFailure {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
            detail = detail.map(d -> d.length() > MAX_DETAIL ? d.substring(0, MAX_DETAIL) : d);
        }

        public static ProviderFailure of(Kind kind, String detail) {
            return new ProviderFailure(kind, Optional.ofNullable(detail), switch (kind) {
                case RATE_LIMIT, UPSTREAM, TRANSPORT -> true;
                case AUTH, INVALID_REQUEST, EGRESS_DENIED, UNKNOWN_MODEL -> false;
            });
        }
    }

    /** Incremental output during a streaming exchange. */
    sealed interface StreamEvent {

        /** A chunk of assistant prose. */
        record TextDelta(String text) implements StreamEvent {
            public TextDelta {
                Objects.requireNonNull(text, "text");
            }
        }

        /** The model began a tool call. Arguments arrive separately and may be partial. */
        record ToolUseStarted(String callId, String name) implements StreamEvent {
            public ToolUseStarted {
                Objects.requireNonNull(callId, "callId");
                Objects.requireNonNull(name, "name");
            }
        }

        /** The exchange finished; carries the fully assembled response. */
        record Completed(ModelResponse response) implements StreamEvent {
            public Completed {
                Objects.requireNonNull(response, "response");
            }
        }
    }
}
