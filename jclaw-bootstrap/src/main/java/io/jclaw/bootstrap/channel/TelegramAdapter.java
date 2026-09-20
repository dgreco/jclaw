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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Telegram, over webhook updates.
 *
 * <p>Telegram does not sign requests. What it offers instead is a secret token, chosen when the
 * webhook is registered and echoed in a header on every delivery, so verification is a constant
 * time comparison rather than an HMAC. That is weaker than a signature, since it does not bind
 * the body, and it is what the platform provides.
 *
 * <p>The bot token doubles as part of the API path, which is why the send URL is built per call
 * and why the token is never logged: a URL containing it is a credential.
 */
public final class TelegramAdapter implements ChannelAdapter {

    private static final String API = "https://api.telegram.org";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client;
    private final String api;

    public TelegramAdapter() {
        this(API);
    }

    /** @param api the API base, overridable so a test can point it at a local server */
    public TelegramAdapter(String api) {
        this.api = Objects.requireNonNull(api, "api");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() {
        return "telegram";
    }

    @Override
    public String apiHost() {
        return URI.create(api).getHost();
    }

    @Override
    public Optional<String> sendUrl(ReplyTarget target) {
        // The real URL carries the bot token, so the guarded form is the base without it.
        return Optional.of(api + "/sendMessage");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result<Delivery, String> receive(Map<String, String> headers, String body, String verifySecret) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(verifySecret, "verifySecret");

        String presented = headers.get("x-telegram-bot-api-secret-token");
        if (presented == null) {
            return Result.err("secret_token_missing");
        }
        if (!MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), verifySecret.getBytes(StandardCharsets.UTF_8))) {
            return Result.err("secret_token_invalid");
        }

        Map<String, Object> update;
        try {
            update = mapper.readValue(body, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            return Result.err("body_malformed");
        }
        Object raw = update.get("message") != null ? update.get("message") : update.get("edited_message");
        if (!(raw instanceof Map<?, ?> message)) {
            return Result.ok(new Delivery.Ignored("no message in the update"));
        }
        Map<String, Object> typed = (Map<String, Object>) message;
        if (typed.get("from") instanceof Map<?, ?> from && Boolean.TRUE.equals(((Map<String, Object>) from).get("is_bot"))) {
            return Result.ok(new Delivery.Ignored("a bot's own message"));
        }
        String text = String.valueOf(typed.getOrDefault("text", "")).trim();
        if (text.isEmpty()) {
            return Result.ok(new Delivery.Ignored("no text"));
        }
        if (!(typed.get("chat") instanceof Map<?, ?> chat)) {
            return Result.ok(new Delivery.Ignored("no chat"));
        }
        String chatId = String.valueOf(((Map<String, Object>) chat).get("id"));
        String author = typed.get("from") instanceof Map<?, ?> from
                ? String.valueOf(((Map<String, Object>) from).getOrDefault("username", "unknown"))
                : "unknown";
        // Telegram's forum topics are the nearest thing it has to threads.
        Optional<String> topic = Optional.ofNullable(typed.get("message_thread_id")).map(String::valueOf);
        return Result.ok(new Delivery.Message(
                new Inbound(text, new ReplyTarget(id(), chatId, topic), author)));
    }

    @Override
    public Result<String, String> send(ReplyTarget target, String text, String token) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(token, "token");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", target.conversation());
        payload.put("text", text);
        target.thread().ifPresent(topic -> payload.put("message_thread_id", topic));
        HttpResponse<String> response;
        try {
            response = client.send(
                    HttpRequest.newBuilder(URI.create(api + "/bot" + token + "/sendMessage"))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                            .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Result.err("telegram_unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        }
        if (response.statusCode() >= 400) {
            // The body can echo the request, and the request path holds the token.
            return Result.err("telegram_http_" + response.statusCode());
        }
        Map<String, Object> answer;
        try {
            answer = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException e) {
            return Result.err("telegram_response_malformed");
        }
        return Boolean.TRUE.equals(answer.get("ok"))
                ? Result.ok("sent")
                : Result.err("telegram_rejected");
    }
}
