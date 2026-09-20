// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.runtime;

import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.model.ContentBlock;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.thread.ThreadService;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.ports.turn.TurnStatus;
import io.jclaw.adapter.out.model.mock.MockModelProvider;
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

/** An attached image reaches the model in the first request and is persisted in the transcript. */
@SpringBootTest
class AttachmentIntegrationTest {

    private static Path workspace;

    @BeforeAll
    static void createWorkspace() throws IOException {
        workspace = Files.createTempDirectory("jclaw-attach-it");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return MockModelProvider.alwaysReplying("it is a square");
        }
    }

    @Autowired
    private JclawRuntime runtime;

    @Autowired
    private ThreadService threads;

    @Autowired
    private ModelProvider provider;

    @Test
    @DisplayName("an image block is sent to the model and kept in the transcript")
    void imageTravelsAndPersists() {
        ThreadId thread = new ThreadId("attach-it");
        ChatMessage inbound = new ChatMessage(ChatMessage.Role.USER, List.of(
                new ContentBlock.Text("what shape is this?"),
                new ContentBlock.Image("image/png", "iVBORw0KGgo=")));

        JclawRuntime.TurnResult result = runtime.submit(thread, inbound, new AtomicBoolean(false), Optional.empty());

        assertEquals(TurnStatus.COMPLETED, result.status());
        List<ContentBlock> sent = ((MockModelProvider) provider).lastRequest().orElseThrow()
                .messages().get(0).content();
        assertTrue(sent.stream().anyMatch(ContentBlock.Image.class::isInstance), "the image reached the model");

        ChatMessage stored = threads.history(thread, 10).get(0).message();
        assertTrue(stored.content().stream().anyMatch(ContentBlock.Image.class::isInstance),
                "the transcript keeps the attachment");
        assertEquals("what shape is this?", stored.displayText());
    }
}
