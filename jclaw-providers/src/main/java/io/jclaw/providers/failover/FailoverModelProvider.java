package io.jclaw.providers.failover;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelExchange.ModelResponse;
import io.jclaw.contracts.model.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tries providers in order, skipping any in cooldown.
 *
 * <p>Two rules keep this from making a bad situation worse:
 *
 * <ul>
 *   <li><b>Only retryable failures advance the chain.</b> A rate limit or a 5xx means "try someone
 *       else"; a malformed request means every provider will reject it identically, so failing over
 *       just multiplies the damage. The chain stops immediately on a non-retryable failure.</li>
 *   <li><b>A failed provider enters a cooldown.</b> Without it, a provider that is down is retried
 *       on every single turn, adding its full timeout to every request.</li>
 * </ul>
 *
 * <p>The clock is injected, so cooldown behaviour is testable without sleeping.
 */
public final class FailoverModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(FailoverModelProvider.class);

    /** How long a provider is skipped after a retryable failure. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    private final List<ModelProvider> chain;
    private final Duration cooldown;
    private final Clock clock;
    private final Map<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    public FailoverModelProvider(List<ModelProvider> chain, Duration cooldown, Clock clock) {
        Objects.requireNonNull(chain, "chain");
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("failover chain must not be empty");
        }
        this.chain = List.copyOf(chain);
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FailoverModelProvider(List<ModelProvider> chain, Clock clock) {
        this(chain, DEFAULT_COOLDOWN, clock);
    }

    @Override
    public String id() {
        return "failover";
    }

    @Override
    public boolean supports(String model) {
        return chain.stream().anyMatch(provider -> provider.supports(model));
    }

    @Override
    public Result<ModelResponse, ProviderFailure> complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();

        ProviderFailure lastFailure = null;
        boolean attemptedAny = false;

        for (ModelProvider provider : chain) {
            if (!provider.supports(request.model())) {
                log.debug("failover: skipping '{}' (does not support model {})",
                        provider.id(), request.model());
                continue;
            }
            if (inCooldown(provider, now)) {
                log.debug("failover: skipping '{}' (cooling down until {})",
                        provider.id(), cooldownUntil.get(provider.id()));
                continue;
            }
            attemptedAny = true;
            log.debug("failover: trying '{}' for model {}", provider.id(), request.model());

            Result<ModelResponse, ProviderFailure> result = provider.complete(request);
            if (result.isOk()) {
                cooldownUntil.remove(provider.id()); // recovered
                log.debug("failover: '{}' succeeded", provider.id());
                return result;
            }

            ProviderFailure failure = result.errorAsOptional().orElseThrow();
            lastFailure = failure;

            if (!failure.retryable()) {
                // A rejected request is rejected everywhere. Failing over would turn one bad
                // request into N bad requests without changing the outcome.
                log.debug("failover: '{}' failed non-retryably ({}); not advancing the chain",
                        provider.id(), failure.kind());
                return result;
            }
            log.debug("failover: '{}' failed retryably ({}); cooling down for {}s",
                    provider.id(), failure.kind(), cooldown.toSeconds());
            cooldownUntil.put(provider.id(), clock.instant().plus(cooldown));
        }

        if (!attemptedAny) {
            log.debug("failover: no provider in the chain accepts model {}", request.model());
            return Result.err(ProviderFailure.of(
                    ProviderFailure.Kind.UPSTREAM,
                    "no provider available for model " + request.model()));
        }
        return Result.err(Objects.requireNonNullElseGet(lastFailure,
                () -> ProviderFailure.of(ProviderFailure.Kind.UPSTREAM, "all providers failed")));
    }

    private boolean inCooldown(ModelProvider provider, Instant now) {
        Instant until = cooldownUntil.get(provider.id());
        return until != null && now.isBefore(until);
    }

    /** Providers currently skipped, for diagnostics. */
    public List<String> providersInCooldown() {
        Instant now = clock.instant();
        return cooldownUntil.entrySet().stream()
                .filter(entry -> now.isBefore(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
