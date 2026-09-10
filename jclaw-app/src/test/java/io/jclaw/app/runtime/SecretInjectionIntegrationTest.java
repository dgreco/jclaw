// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityResultStore;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.event.JclawEvent;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.providers.mock.MockModelProvider.Script;
import org.junit.jupiter.api.AfterAll;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A vault secret reaches the bound host as a real header, and nothing else ever holds it: not the
 * transcript, not the events, not the stored result, not the state directory.
 */
@SpringBootTest
class SecretInjectionIntegrationTest {

    private static final String VALUE = "tok-super-secret-0123456789";

    private static Path workspace;
    private static HttpServer server;
    private static int port;
    private static final List<String> seenAuthorization = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-secrets-it");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            // Echo the header back: the redactor must mask the leased value in the result.
            byte[] body = ("you sent " + exchange.getRequestHeaders().getFirst("Authorization"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jclaw.workspace", () -> workspace.toString());
        registry.add("jclaw.state-dir", () -> workspace.resolve(".state").toString());
        registry.add("jclaw.approval-mode", () -> "trusted");
        registry.add("jclaw.allow-private-networks", () -> "true");
    }

    @TestConfiguration
    static class ScriptedProvider {
        @Bean
        @Primary
        ModelProvider scriptedModelProvider() {
            return new MockModelProvider(List.of(
                    new Script.ToolCall("f1", "builtin.http_fetch", Map.of(
                            "url", "http://127.0.0.1:" + port + "/me",
                            "headers", Map.of("Authorization", "Bearer {{secret:api}}"))),
                    new Script.Text("first done"),
                    new Script.ToolCall("f2", "builtin.http_fetch", Map.of(
                            "url", "http://localhost:" + port + "/me",
                            "headers", Map.of("Authorization", "Bearer {{secret:api}}"))),
                    new Script.Text("second done")));
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired SecretVault vault;
    @Autowired EventLog events;
    @Autowired ThreadService threads;
    @Autowired CapabilityResultStore results;

    @Test
    @DisplayName("the bound host gets the value; an unbound host gets a denial; no store holds the value")
    void injectsOnlyWhereBound() throws IOException {
        vault.put(new SecretName("api"), VALUE,
                new Binding(CapabilityId.of("builtin.http_fetch"), Set.of("127.0.0.1")));

        ThreadId thread = new ThreadId("secrets");
        JclawRuntime.TurnResult first = runtime.submit(thread,
                ChatMessage.user("call the api"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, first.status(), () -> String.valueOf(first.failureDetail()));
        assertEquals(List.of("Bearer " + VALUE), seenAuthorization, "the server received the real credential");

        List<JclawEvent> firstEvents = events.readRun(first.run()).stream().map(EventLog.Entry::event).toList();
        assertTrue(firstEvents.stream().anyMatch(e -> e instanceof JclawEvent.SecretInjected s
                && s.secret().equals("api")), "the audit log names the secret that was used");
        assertTrue(firstEvents.stream().anyMatch(e -> e instanceof JclawEvent.CapabilityInvoked c
                && c.outcome().equals("ok")));

        JclawRuntime.TurnResult second = runtime.submit(thread,
                ChatMessage.user("now the other host"), new AtomicBoolean(false), Optional.empty());
        assertEquals(TurnStatus.COMPLETED, second.status());
        assertEquals(1, seenAuthorization.size(), "localhost is not a bound host: no request was made");
        assertTrue(events.readRun(second.run()).stream().map(EventLog.Entry::event).anyMatch(e -> e instanceof JclawEvent.CapabilityInvoked c
                && c.outcome().equals("denied")), "the model was told the call was refused");

        // Nothing durable holds the value. The checkpoints keep the reference the model wrote,
        // and the stored result holds the echoing server's reply with the value masked.
        boolean reference = false;
        boolean masked = false;
        try (var files = Files.walk(workspace.resolve(".state"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file);
                assertFalse(content.contains(VALUE), file + " holds the secret value");
                reference |= content.contains("{{secret:api}}");
                masked |= content.contains("you sent Bearer [REDACTED]");
            }
        }
        assertTrue(reference, "the state keeps the reference form the model wrote");
        assertTrue(masked, "an echoing server's response is masked before it is stored or shown");
        assertFalse(threads.history(thread, Integer.MAX_VALUE).toString().contains(VALUE));
    }
}
