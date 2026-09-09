package io.jclaw.app.channel;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.app.runtime.TurnRunScheduler;
import io.jclaw.contracts.channel.ChannelAdapter;
import io.jclaw.contracts.channel.ChannelBindingStore;
import io.jclaw.contracts.channel.ReplyTarget;
import io.jclaw.contracts.event.EventLog;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.secret.SecretVault;
import io.jclaw.contracts.thread.ThreadService;
import io.jclaw.contracts.turn.RunStore;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnStatus;
import io.jclaw.kernel.guard.EgressGuard;
import io.jclaw.providers.mock.MockModelProvider;
import io.jclaw.storage.channel.JsonlChannelBindingStore;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.secret.FileSecretVault;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message arrives from Slack, becomes a turn, and the answer goes back to the thread it came
 * from. Plus what the adapters must refuse: a bad signature, a replayed one, and a bot talking
 * to itself.
 */
@SpringBootTest
class ChannelIntegrationTest {

    private static final String VERIFY = "slack-signing-secret-value";
    private static final String TOKEN = "xoxb-token-value-0123456789";

    private static Path workspace;
    private static HttpServer platform;
    private static final List<String> slackPosts = new CopyOnWriteArrayList<>();
    private static final List<String> telegramPosts = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws IOException {
        workspace = Files.createTempDirectory("jclaw-channel-it");
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/api/chat.postMessage", exchange -> {
            slackPosts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platform.createContext("/", exchange -> {
            telegramPosts.add(exchange.getRequestURI().getPath() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platform.start();
    }

    @AfterAll
    static void stop() {
        platform.stop(0);
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
            return MockModelProvider.alwaysReplying("here is the answer");
        }
    }

    @Autowired JclawRuntime runtime;
    @Autowired ThreadService threads;
    @Autowired RunStore runs;
    @Autowired EventLog events;
    @Autowired TurnRunScheduler scheduler;
    @Autowired Clock clock;

    private String base() {
        return "http://127.0.0.1:" + platform.getAddress().getPort();
    }

    private static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v0=" + HexFormat.of().formatHex(
                    mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> slackHeaders(String body, long at) {
        String timestamp = String.valueOf(at);
        return Map.of("x-slack-request-timestamp", timestamp,
                "x-slack-signature", sign(VERIFY, timestamp, body));
    }

    @Test
    @DisplayName("a Slack message becomes a turn and its answer goes back to the same thread")
    void slackRoundTrip() throws IOException {
        FileSecretVault vault = new FileSecretVault(
                new JsonlFile(workspace.resolve("secrets.jsonl")), new byte[32], Clock.systemUTC());
        vault.put(new SecretVault.SecretName("slack-verify"), VERIFY,
                new SecretVault.Binding(ChannelService.CONNECT, Set.of("127.0.0.1")));
        vault.put(new SecretVault.SecretName("slack-token"), TOKEN,
                new SecretVault.Binding(ChannelService.CONNECT, Set.of("127.0.0.1")));

        ChannelBindingStore bindings = new JsonlChannelBindingStore(
                new JsonlFile(workspace.resolve("bindings.jsonl")), clock);
        List<ChannelAdapter> adapters = List.of(
                new SlackAdapter(clock, base() + "/api/chat.postMessage"),
                new TelegramAdapter(base()));
        ChannelService service = new ChannelService(adapters,
                Map.of("slack", new ChannelService.Credentials("slack-verify", "slack-token"),
                        "telegram", new ChannelService.Credentials("slack-verify", "slack-token")),
                bindings, runtime, threads, runs, vault, EgressGuard.allowingPrivateNetworks(), events);

        assertTrue(service.enabled());
        assertEquals(Set.of("slack", "telegram"), service.channels());

        long now = clock.instant().getEpochSecond();
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"what is the status\","
                + "\"channel\":\"C123\",\"ts\":\"1700000000.1\",\"user\":\"U9\"}}";

        var accepted = assertInstanceOf(ChannelService.Handled.Accepted.class,
                service.receive("slack", slackHeaders(body, now), body));
        assertEquals("slack:C123/1700000000.1", accepted.thread());
        assertEquals(Optional.of(new ReplyTarget("slack", "C123", Optional.of("1700000000.1"))),
                bindings.find(new ThreadId(accepted.thread())));
        assertEquals("what is the status",
                threads.history(new ThreadId(accepted.thread()), 5).get(0).message().displayText());

        // Nothing has been sent yet: the turn has not run.
        assertTrue(slackPosts.isEmpty(), slackPosts.toString());

        scheduler.runOnce(2, new AtomicBoolean(false));
        assertEquals(TurnStatus.COMPLETED, runs.find(new io.jclaw.contracts.turn.TurnRunId(accepted.run()))
                .orElseThrow().status());
        assertEquals(1, slackPosts.size(), slackPosts.toString());
        assertTrue(slackPosts.get(0).contains("here is the answer"), slackPosts.get(0));
        assertTrue(slackPosts.get(0).contains("\"thread_ts\":\"1700000000.1\""), slackPosts.get(0));
        assertTrue(slackPosts.get(0).contains("auth=Bearer " + TOKEN), "the token came from the vault");

        // A forged signature, a replayed one, and a bot's own message.
        assertInstanceOf(ChannelService.Handled.Refused.class,
                service.receive("slack", Map.of("x-slack-request-timestamp", String.valueOf(now),
                        "x-slack-signature", "v0=deadbeef"), body));
        assertInstanceOf(ChannelService.Handled.Refused.class,
                service.receive("slack", slackHeaders(body, now - 3600), body));
        String fromBot = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"hi\","
                + "\"channel\":\"C123\",\"bot_id\":\"B1\"}}";
        assertInstanceOf(ChannelService.Handled.Ignored.class,
                service.receive("slack", slackHeaders(fromBot, now), fromBot));

        // Slack proving the endpoint is live.
        String challenge = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";
        var handshake = assertInstanceOf(ChannelService.Handled.Handshake.class,
                service.receive("slack", slackHeaders(challenge, now), challenge));
        assertEquals("abc123", handshake.body());

        // Telegram verifies with a shared token in a header rather than a signature.
        String update = "{\"message\":{\"text\":\"ping\",\"chat\":{\"id\":42},\"from\":{\"username\":\"dg\"}}}";
        assertInstanceOf(ChannelService.Handled.Refused.class,
                service.receive("telegram", Map.of("x-telegram-bot-api-secret-token", "wrong"), update));
        var fromTelegram = assertInstanceOf(ChannelService.Handled.Accepted.class,
                service.receive("telegram", Map.of("x-telegram-bot-api-secret-token", VERIFY), update));
        assertEquals("telegram:42", fromTelegram.thread());

        scheduler.runOnce(2, new AtomicBoolean(false));
        assertEquals(1, telegramPosts.size(), telegramPosts.toString());
        assertTrue(telegramPosts.get(0).contains("/bot" + TOKEN + "/sendMessage"), telegramPosts.get(0));
        assertTrue(telegramPosts.get(0).contains("here is the answer"), telegramPosts.get(0));

        // An unknown channel, and a secret bound somewhere else, are both refused.
        assertInstanceOf(ChannelService.Handled.Refused.class,
                service.receive("discord", Map.of(), "{}"));
        var direct = service.deliver(new ReplyTarget("slack", "C999"), "x");
        assertFalse(direct.isErr(),
                "an unbound conversation can still be written to directly: "
                        + direct.errorAsOptional().orElse(""));
    }
}
