// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.channel;

import io.jclaw.ports.Result;
import io.jclaw.ports.channel.ChannelAdapter;
import io.jclaw.ports.channel.ReplyTarget;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Slack, over the Events API.
 *
 * <p>Inbound requests are signed, and the signature is checked before anything else is read. The
 * scheme is Slack's: HMAC-SHA256 over {@code v0:{timestamp}:{body}} keyed by the signing secret,
 * compared in constant time. The timestamp is checked too, within five minutes, because a valid
 * signature on an old body is a replay, and a webhook that replays is a webhook that can be made
 * to run a turn twice.
 *
 * <p>Bot messages are ignored. Without that, jclaw answering in a channel produces an event that
 * looks like a message, which produces an answer, which produces an event: the loop is immediate
 * and only stops when something runs out.
 */
public final class SlackAdapter implements ChannelAdapter {

    private static final String API = "https://slack.com/api/chat.postMessage";
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client;
    private final Clock clock;
    private final String api;

    public SlackAdapter(Clock clock) {
        this(clock, API);
    }

    /** @param api the post endpoint, overridable so a test can point it at a local server */
    public SlackAdapter(Clock clock, String api) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.api = Objects.requireNonNull(api, "api");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() {
        return "slack";
    }

    @Override
    public String apiHost() {
        return URI.create(api).getHost();
    }

    @Override
    public Optional<String> sendUrl(ReplyTarget target) {
        return Optional.of(api);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result<Delivery, String> receive(Map<String, String> headers, String body, String verifySecret) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(verifySecret, "verifySecret");

        String timestamp = headers.get("x-slack-request-timestamp");
        String signature = headers.get("x-slack-signature");
        if (timestamp == null || signature == null) {
            return Result.err("signature_missing");
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.err("timestamp_malformed");
        }
        if (Math.abs(clock.instant().getEpochSecond() - sent) > MAX_SKEW.toSeconds()) {
            return Result.err("timestamp_outside_window");
        }
        String expected = "v0=" + hmacHex(verifySecret, "v0:" + timestamp + ":" + body);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
            return Result.err("signature_invalid");
        }

        Map<String, Object> payload;
        try {
            payload = mapper.readValue(body, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            return Result.err("body_malformed");
        }

        // Slack proves an endpoint is live by asking it to echo a challenge.
        if ("url_verification".equals(payload.get("type"))) {
            return Result.ok(new Delivery.Handshake(String.valueOf(payload.getOrDefault("challenge", ""))));
        }
        if (!(payload.get("event") instanceof Map<?, ?> raw)) {
            return Result.ok(new Delivery.Ignored("not an event callback"));
        }
        Map<String, Object> event = (Map<String, Object>) raw;
        if (!"message".equals(event.get("type")) && !"app_mention".equals(event.get("type"))) {
            return Result.ok(new Delivery.Ignored("event type " + event.get("type")));
        }
        if (event.get("bot_id") != null || "bot_message".equals(event.get("subtype"))) {
            return Result.ok(new Delivery.Ignored("a bot's own message"));
        }
        if (event.get("subtype") != null) {
            // Edits, deletions, joins, and the rest are not something to answer.
            return Result.ok(new Delivery.Ignored("message subtype " + event.get("subtype")));
        }
        String text = String.valueOf(event.getOrDefault("text", "")).trim();
        String channel = String.valueOf(event.getOrDefault("channel", ""));
        if (text.isEmpty() || channel.isEmpty()) {
            return Result.ok(new Delivery.Ignored("no text or no channel"));
        }
        // Answer in the thread the message is in, or start one on the message itself, so a busy
        // channel does not become an interleaving of unrelated conversations.
        String thread = event.get("thread_ts") instanceof String existing
                ? existing
                : String.valueOf(event.getOrDefault("ts", ""));
        return Result.ok(new Delivery.Message(new Inbound(text,
                new ReplyTarget(id(), channel, thread.isEmpty() ? Optional.empty() : Optional.of(thread)),
                String.valueOf(event.getOrDefault("user", "unknown")))));
    }

    @Override
    public Result<String, String> send(ReplyTarget target, String text, String token) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(token, "token");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", target.conversation());
        payload.put("text", text);
        target.thread().ifPresent(thread -> payload.put("thread_ts", thread));

        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Result.err("slack_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        }
        if (response.statusCode() >= 400) {
            return Result.err("slack_http_" + response.statusCode());
        }
        // Slack answers 200 with ok:false and a machine-readable error for real failures. The
        // parse is the only thing wrapped: a broad catch here once turned a bug of mine into a
        // reported platform failure.
        Map<String, Object> answer;
        try {
            answer = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            return Result.err("slack_response_malformed");
        }
        if (Boolean.TRUE.equals(answer.get("ok"))) {
            return Result.ok(String.valueOf(answer.getOrDefault("ts", "sent")));
        }
        return Result.err("slack_" + answer.getOrDefault("error", "rejected"));
    }

    private static String hmacHex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is mandatory in every JDK", e);
        }
    }
}
