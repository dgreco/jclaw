package io.jclaw.domain.trigger;

import io.jclaw.contracts.Result;
import io.jclaw.domain.cron.CronSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What makes a routine fire, parsed from the one-line expression a routine stores.
 *
 * <p>Four forms, told apart by their first word so an existing cron routine keeps working
 * unchanged:
 * <ul>
 *   <li>{@code 0 9 * * MON-FRI}: a five-field cron expression, evaluated in the routine's zone;</li>
 *   <li>{@code every 30m} (or an ISO duration, {@code every PT30M}): a heartbeat, due once the
 *       interval has passed since the last firing, or since creation;</li>
 *   <li>{@code webhook sha256:<hex>}: fired by {@code POST /hooks/<routine name>} presenting the
 *       secret whose SHA-256 this is. Only the hash is stored; the secret is shown once;</li>
 *   <li>{@code on run.finished status=FAILED} or {@code on gate.raised kind=APPROVAL}: fired
 *       when a matching audit event is written for a run that is not itself a routine's.</li>
 * </ul>
 */
public sealed interface Trigger {

    /** Event types an {@link OnEvent} trigger may name. Kept short so a trigger cannot chase itself. */
    Set<String> EVENT_TYPES = Set.of("run.finished", "gate.raised");

    record Cron(String expression) implements Trigger {
        public Cron {
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Heartbeat(Duration interval) implements Trigger {
        public Heartbeat {
            Objects.requireNonNull(interval, "interval");
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("interval must be positive");
            }
        }
    }

    /** @param secretHash lower-case hex SHA-256 of the bearer secret */
    record Webhook(String secretHash) implements Trigger {
        public Webhook {
            Objects.requireNonNull(secretHash, "secretHash");
            if (!secretHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("secretHash must be 64 hex digits");
            }
        }

        /** Whether a presented secret is the one this webhook was created with. */
        public boolean accepts(String presented) {
            return presented != null && MessageDigest.isEqual(
                    hashSecret(presented).getBytes(StandardCharsets.US_ASCII),
                    secretHash.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** @param filters attribute equalities the event must satisfy, such as {@code status=FAILED} */
    record OnEvent(String type, Map<String, String> filters) implements Trigger {
        public OnEvent {
            Objects.requireNonNull(type, "type");
            filters = Map.copyOf(Objects.requireNonNull(filters, "filters"));
            if (!EVENT_TYPES.contains(type)) {
                throw new IllegalArgumentException("event triggers may name " + EVENT_TYPES + ", not '" + type + "'");
            }
        }

        /** Whether an event of {@code type} with these attributes matches. */
        public boolean matches(String eventType, Map<String, String> attributes) {
            if (!type.equals(eventType)) {
                return false;
            }
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                if (!filter.getValue().equalsIgnoreCase(attributes.get(filter.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Parses a trigger expression, or explains why it is not one. */
    static Result<Trigger, String> parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return Result.err("trigger_required");
        }
        String[] words = trimmed.split("\\s+");
        try {
            return switch (words[0].toLowerCase(Locale.ROOT)) {
                case "every" -> words.length == 2
                        ? Result.ok(new Heartbeat(parseDuration(words[1])))
                        : Result.err("heartbeat_needs_one_interval");
                case "webhook" -> words.length == 2 && words[1].toLowerCase(Locale.ROOT).startsWith("sha256:")
                        ? Result.ok(new Webhook(words[1].substring("sha256:".length()).toLowerCase(Locale.ROOT)))
                        : Result.err("webhook_needs_sha256_hash");
                case "on" -> {
                    if (words.length < 2) {
                        yield Result.err("event_trigger_needs_type");
                    }
                    Map<String, String> filters = new LinkedHashMap<>();
                    for (int i = 2; i < words.length; i++) {
                        int eq = words[i].indexOf('=');
                        if (eq <= 0) {
                            yield Result.err("event_filter_needs_key=value");
                        }
                        filters.put(words[i].substring(0, eq), words[i].substring(eq + 1));
                    }
                    yield Result.ok(new OnEvent(words[1].toLowerCase(Locale.ROOT), filters));
                }
                default -> {
                    CronSpec.parse(trimmed);
                    yield Result.ok(new Cron(trimmed));
                }
            };
        } catch (IllegalArgumentException e) {
            return Result.err("invalid_trigger: " + e.getMessage());
        }
    }

    /** The expression form, as stored. */
    default String expression() {
        return switch (this) {
            case Cron cron -> cron.expression();
            case Heartbeat heartbeat -> "every " + heartbeat.interval();
            case Webhook webhook -> "webhook sha256:" + webhook.secretHash();
            case OnEvent event -> "on " + event.type() + event.filters().entrySet().stream()
                    .map(entry -> " " + entry.getKey() + "=" + entry.getValue())
                    .reduce("", String::concat);
        };
    }

    /** Whether the clock decides when this fires (as opposed to a request or an event). */
    default boolean timeDriven() {
        return this instanceof Cron || this instanceof Heartbeat;
    }

    /** Hex SHA-256 of a webhook secret. */
    static String hashSecret(String secret) {
        Objects.requireNonNull(secret, "secret");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    private static Duration parseDuration(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("pt") || lower.startsWith("p")) {
            return Duration.parse(text.toUpperCase(Locale.ROOT));
        }
        if (lower.matches("\\d+[smhd]")) {
            long amount = Long.parseLong(lower.substring(0, lower.length() - 1));
            return switch (lower.charAt(lower.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        throw new IllegalArgumentException("not an interval: '" + text + "' (try 30m, 2h, 1d, or PT30M)");
    }
}
