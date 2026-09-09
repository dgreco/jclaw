package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.event.EventLog;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A capability denied in configuration is denied at the kernel even in {@code trusted} mode, and
 * is not published to the model in the first place.
 */
@SpringBootTest
class DeniedCapabilityIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-denied-it");
        Files.writeString(workspace.resolve("notes.md"), "secret notes");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.denied-capabilities", () -> "builtin.read_file,builtin.shell");
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
    @DisplayName("a denied capability is refused by the kernel and absent from the model's surface")
    void deniedAtKernelAndHiddenFromSurface() {
        ((MockModelProvider) provider).reprogram(List.of(
                new Script.ToolCall("c1", "builtin.read_file", Map.of("path", "notes.md")),
                new Script.Text("I was not allowed to read it.")));

        JclawRuntime.TurnResult result =
                runtime.submit(new ThreadId("denied-it"), "read notes.md", new AtomicBoolean(false));

        assertEquals(TurnStatus.COMPLETED, result.status(), "a denial is data the loop handles");
        assertEquals("I was not allowed to read it.", result.reply().orElseThrow());

        String invoked = events.readRun(result.run()).stream()
                .map(EventLog.Entry::event)
                .filter(event -> event.type().equals("capability.invoked"))
                .map(Object::toString)
                .reduce("", String::concat);
        assertTrue(invoked.contains("denied"), "the kernel should record the denial: " + invoked);

        List<String> surface = runtime.visibleCapabilities().stream()
                .map(CapabilityDescriptor::id).map(id -> id.value()).toList();
        assertFalse(surface.contains("builtin.read_file"), "denied tools are not published");
        assertFalse(surface.contains("builtin.shell"));
        assertTrue(surface.contains("builtin.list_dir"), "the rest of the surface is intact");
    }
}
