package io.jclaw.app.runtime;

import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelExchange.ModelRequest;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.thread.ThreadService;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context policy at the product surface: a long thread reaches the model as a bounded,
 * structurally valid window that says what it left out, while the transcript keeps everything.
 */
@SpringBootTest
class ContextWindowIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-ctx-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.context-max-messages", () -> "2");
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
    private ThreadService threads;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("a long thread is compacted into a valid window that reports what was omitted")
    void thirdTurnSeesBoundedWindow() {
        MockModelProvider mock = (MockModelProvider) provider;
        ThreadId thread = new ThreadId("ctx-it");

        mock.reprogram(List.of(new Script.Text("reply one")));
        assertEquals(TurnStatus.COMPLETED, runtime.submit(thread, "first", new AtomicBoolean(false)).status());
        mock.reprogram(List.of(new Script.Text("reply two")));
        assertEquals(TurnStatus.COMPLETED, runtime.submit(thread, "second", new AtomicBoolean(false)).status());
        mock.reprogram(List.of(new Script.Text("reply three")));
        assertEquals(TurnStatus.COMPLETED, runtime.submit(thread, "third", new AtomicBoolean(false)).status());

        ModelRequest request = mock.lastRequest().orElseThrow();
        List<ChatMessage> sent = request.messages();
        // Transcript before the third turn: first, reply one, second, reply two, third (5).
        // Window of 2 = [reply two, third]; an assistant message cannot open a request, so a
        // synthetic user notice is placed in front of it.
        assertEquals(3, sent.size(), "notice + the two newest messages");
        assertEquals(ChatMessage.Role.USER, sent.get(0).role());
        assertTrue(sent.get(0).displayText().contains("3 earlier messages"),
                "the notice counts what was really dropped, not what a pre-sliced window saw");
        assertEquals("reply two", sent.get(1).displayText());
        assertEquals("third", sent.get(2).displayText());

        assertEquals(6, threads.history(thread, Integer.MAX_VALUE).size(),
                "the transcript keeps the whole conversation");
    }
}
