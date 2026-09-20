// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.event.EventLog;
import io.jclaw.ports.event.JclawEvent;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
import io.jclaw.adapter.out.model.mock.MockModelProvider.Script;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reflective family reviews a draft in a second, non-streamed call and persists the revision. */
@SpringBootTest
class ReflectiveIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-reflective-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.loop-family", () -> "reflective");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of(new Script.Text("first draft"), new Script.Text("polished reply")));
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired MockModelProvider provider;
    @Autowired ThreadService threads;
    @Autowired EventLog events;

    @Test
    @DisplayName("two model calls, one persisted reply: the revision")
    void reflects() {
        StringBuilder streamed = new StringBuilder();
        JclawRuntime.TurnResult result = runtime.submit(new ThreadId("reflect"), ChatMessage.user("explain"),
                new AtomicBoolean(false), Optional.of(event -> {
                    if (event instanceof ModelProvider.StreamEvent.TextDelta delta) {
                        streamed.append(delta.text());
                    }
                }));
        assertEquals(TurnStatus.COMPLETED, result.status(), () -> String.valueOf(result.failureDetail()));
        assertEquals(Optional.of("polished reply"), result.reply());
        assertEquals(List.of("explain", "polished reply"),
                threads.history(new ThreadId("reflect"), 10).stream().map(m -> m.message().displayText()).toList());
        assertTrue(provider.lastRequest().orElseThrow().messages().stream()
                .anyMatch(m -> m.displayText().contains("Review your previous reply")), "the review call carried the instruction");
        assertTrue(provider.lastRequest().orElseThrow().tools().isEmpty(), "the review call offers no tools");
        assertEquals(2, events.readRun(result.run()).stream().map(EventLog.Entry::event)
                .filter(JclawEvent.ModelCalled.class::isInstance).count());
        assertEquals("first draft", streamed.toString(), "only the user-facing call streams");
    }
}
