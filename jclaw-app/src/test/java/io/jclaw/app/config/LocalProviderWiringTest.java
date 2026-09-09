package io.jclaw.app.config;

import io.jclaw.contracts.model.ModelProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring for the {@code local} provider: any OpenAI-compatible inference server.
 *
 * <p>The interesting behaviour is the refusal: {@code jclaw.provider=local} without a base URL
 * must fail at startup with a message that says what to set, because there is no port local
 * inference servers agree on — a guessed default would surface as a connection error that reads
 * like a jclaw bug.
 */
class LocalProviderWiringTest {

    private final JclawConfiguration configuration = new JclawConfiguration();

    private static JclawProperties properties(String provider, String localBaseUrl) {
        return new JclawProperties(
                Path.of("."),
                Path.of("build", "test-state"),
                "qwen2.5-coder-7b-instruct",
                provider,
                "https://api.openai.com/v1",
                "http://localhost:11434/v1",
                localBaseUrl,
                "https://openrouter.ai/api/v1",
                "",
                "jclaw",
                "interactive",
                false,
                25,
                500_000,
                200,
                100_000,
                "system",
                List.of(),
                "none",
                "",
                List.of(),
                List.of(),
                List.of(),
                "sanitize",
                true,
                1024,
                java.time.Duration.ofHours(24),
                java.util.Map.of(),
                java.util.Map.of());
    }

    @Test
    @DisplayName("provider=local with a base URL wires the generic OpenAI-compatible adapter")
    void wiresLocalProvider() {
        ModelProvider provider = configuration.modelProvider(
                properties("local", "http://localhost:1234/v1"), Clock.systemUTC());

        assertEquals("local", provider.id(),
                "the event log and 'jclaw models' must name what the operator configured");
    }

    @Test
    @DisplayName("provider=local without a base URL fails at startup with the property to set")
    void refusesLocalWithoutBaseUrl() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> configuration.modelProvider(properties("local", ""), Clock.systemUTC()));

        assertTrue(failure.getMessage().contains("jclaw.local-base-url"),
                "the error must name the missing property, got: " + failure.getMessage());
    }

    @Test
    @DisplayName("the unknown-provider error lists local among the valid choices")
    void unknownProviderErrorMentionsLocal() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> configuration.modelProvider(properties("nope", ""), Clock.systemUTC()));

        assertTrue(failure.getMessage().contains("local"),
                "the choices list must stay in sync, got: " + failure.getMessage());
    }
}
