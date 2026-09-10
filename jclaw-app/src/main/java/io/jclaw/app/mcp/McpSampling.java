// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.domain.mcp.SamplingPolicy;
import io.jclaw.tools.mcp.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Answers an MCP server's {@code sampling/createMessage}: the one request that travels the other
 * way.
 *
 * <p>Sampling is what lets a server do something an agent is good at — summarise what it just
 * read, name a file, decide which of two branches to take — without shipping a model key of its
 * own. It is also the one place third-party code can make jclaw spend money, so the interesting
 * part is the limits rather than the plumbing.
 *
 * <p><strong>Off unless the operator turns it on</strong> ({@code jclaw.mcp-sampling}). A server
 * is not asked to behave; it is simply never told the capability exists, so a server that would
 * have used it plans around not having it.
 *
 * <p><strong>Capped per process.</strong> A counter, not a rate: the failure this prevents is a
 * server in a loop, and a loop caught after a hundred calls has already cost a hundred calls. The
 * cap is deliberately small, and reaching it is a refusal the server is told about rather than a
 * silent stall.
 *
 * <p>What the server may influence is narrow, and {@link SamplingPolicy} is where that is
 * decided: no tools, the host's model, bounded tokens, text only.
 */
public final class McpSampling implements McpTransport.ServerRequests {

    private static final Logger log = LoggerFactory.getLogger(McpSampling.class);

    public static final String METHOD = "sampling/createMessage";

    private final String server;
    private final ModelProvider provider;
    private final EventLog events;
    private final Clock clock;
    private final String model;
    private final int maxOutputTokens;
    private final int limit;
    private final AtomicInteger used = new AtomicInteger();

    /**
     * @param server          the MCP server's name, for the audit log
     * @param limit           how many sampled calls this server may make in this process
     */
    public McpSampling(String server, ModelProvider provider, EventLog events, Clock clock,
            String model, int maxOutputTokens, int limit) {
        this.server = Objects.requireNonNull(server, "server");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.model = Objects.requireNonNull(model, "model");
        this.maxOutputTokens = maxOutputTokens;
        this.limit = limit;
    }

    /** Sampled calls made so far, for diagnostics. */
    public int calls() {
        return used.get();
    }

    @Override
    public Result<Map<String, Object>, String> answer(String method, Map<String, Object> params) {
        if (!METHOD.equals(method)) {
            return Result.err("method_not_supported");
        }
        if (used.incrementAndGet() > limit) {
            log.debug("mcp sampling: {} is over its cap of {} call(s)", server, limit);
            return Result.err("sampling_budget_exhausted");
        }
        Result<ModelRequest, String> request = SamplingPolicy.requestFor(params, model, maxOutputTokens);
        if (request.isErr()) {
            return Result.err(request.errorAsOptional().orElse("sampling_request_invalid"));
        }

        long startedAt = clock.millis();
        Result<ModelResponse, ModelProvider.ProviderFailure> answered = provider.complete(request.orElseThrow());
        long elapsed = clock.millis() - startedAt;
        if (answered.isErr()) {
            log.debug("mcp sampling: {} failed at the provider", server);
            return Result.err("sampling_model_failed");
        }
        ModelResponse response = answered.orElseThrow();
        // Audited like any other model call, under a synthetic run id naming the server. A
        // sampled call has no run of its own, and leaving it out of the log would make an MCP
        // server the one caller whose spending does not appear anywhere.
        events.append(new io.jclaw.contracts.event.JclawEvent.ModelCalled(
                clock.instant(), samplingRun(server), provider.id(), response.modelId(),
                response.usage(), elapsed));
        log.debug("mcp sampling: {} sampled {} in {} ms ({} of {} calls)",
                server, response.modelId(), elapsed, used.get(), limit);
        return Result.ok(SamplingPolicy.reply(response.text(), response.modelId()));
    }

    /**
     * The run id a sampled call is audited under.
     *
     * <p>Not a real run: nothing resumes it and no checkpoint names it. It exists so the event
     * has somewhere to hang, and it is prefixed distinctly so nobody mistakes it for a turn.
     */
    static TurnRunId samplingRun(String server) {
        return new TurnRunId("run_mcpsample_" + server.replaceAll("[^A-Za-z0-9_-]", "_"));
    }
}
