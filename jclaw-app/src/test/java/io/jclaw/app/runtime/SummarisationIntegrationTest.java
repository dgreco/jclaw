package io.jclaw.app.runtime;

import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.mock.MockModelProvider.Script;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Summarisation as an effect at the product surface: a long thread gets one extra, non-streamed
 * model call whose answer replaces the dropped span in the real request.
 */
@SpringBootTest
class SummarisationIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-summary-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        // Three: the second turn (three messages) still fits; the third (five) drops two.
        registry.add("jclaw.context-max-messages", () -> "3");
        registry.add("jclaw.context-summarise", () -> "true");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of());
        }
    }

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private EventLog events;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("the third turn summarises the first two and the reply request carries the summary")
    void summarisesDroppedHistory() {
        MockModelProvider mock = (MockModelProvider) provider;
        ThreadId thread = new ThreadId("summary-it");
        mock.reprogram(List.of(new Script.Text("reply one")));
        runtime.submit(thread, "first", new AtomicBoolean(false));
        mock.reprogram(List.of(new Script.Text("reply two")));
        runtime.submit(thread, "second", new AtomicBoolean(false));

        // Third turn: the first model call is the summary, the second the real reply.
        mock.reprogram(List.of(
                new Script.Text("The user asked two things and got two replies."),
                new Script.Text("reply three")));
        List<String> streamed = new ArrayList<>();
        JclawRuntime.TurnResult result = runtime.submit(thread, "third", new AtomicBoolean(false),
                java.util.Optional.of(event -> {
                    if (event instanceof ModelProvider.StreamEvent.TextDelta delta) {
                        streamed.add(delta.text());
                    }
                }));

        assertEquals(TurnStatus.COMPLETED, result.status());
        assertEquals("reply three", result.reply().orElseThrow());
        assertEquals(List.of("reply three"), streamed, "the summary never reaches the terminal");

        ModelRequest real = mock.lastRequest().orElseThrow();
        String first = real.messages().get(0).displayText();
        assertTrue(first.startsWith("[Context notice: 2 earlier messages"), first);
        assertTrue(first.contains("Summary of the omitted messages: The user asked two things and got two replies."));
        assertTrue(first.endsWith("second"), "the notice is folded into the first kept user message");
        assertEquals("third", real.messages().get(real.messages().size() - 1).displayText());
        assertFalse(real.messages().stream().map(ChatMessage::displayText).anyMatch("first"::equals),
                "the summarised span is gone from the request");

        long modelCalls = events.readRun(result.run()).stream()
                .map(EventLog.Entry::event)
                .filter(JclawEvent.ModelCalled.class::isInstance)
                .count();
        assertEquals(2, modelCalls, "one summary call plus one real call, both audited");
    }
}
