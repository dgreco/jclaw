package io.jclaw.domain.safety;

import java.util.Locale;

/**
 * What to do with content that arrives from outside — a platform message, a webhook body.
 *
 * <p>Deliberately a separate enum from the kernel's {@code InjectionPolicy}, which governs tool
 * <em>output</em>. They read alike and mean different things: one decides how much of a tool's
 * result the model may see mid-run, the other decides whether a turn starts at all. {@link
 * #REVIEW} only makes sense here, because holding a message costs a human a look, while holding a
 * tool result would strand a run that is already executing.
 *
 * <p>Ordered from least to most intrusive.
 */
public enum InboundPolicy {

    /** No scanning. For a deployment whose inbound corpus makes the heuristics misfire. */
    OFF,

    /** Scan and record a finding; the text reaches the model unchanged. */
    WARN,

    /**
     * Scan, record, and fence: chat-template tokens defused, the content wrapped, and a notice
     * saying it is data from a named source rather than an instruction. The default, and the
     * same treatment tool output already gets.
     */
    SANITIZE,

    /**
     * As {@link #SANITIZE}, except a {@code HIGH} finding holds the message for a human instead
     * of starting a turn. Nothing runs until someone approves it, and approving enqueues the
     * message fenced — a reviewer's "yes" means "this is worth answering", not "treat it as
     * instructions".
     */
    REVIEW,

    /** As {@link #SANITIZE}, except a {@code HIGH} finding is dropped and no turn is started. */
    BLOCK;

    public static InboundPolicy parse(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "warn" -> WARN;
            case "sanitize", "sanitise", "" -> SANITIZE;
            case "review" -> REVIEW;
            case "block" -> BLOCK;
            default -> throw new IllegalArgumentException("unknown inbound policy '" + value
                    + "'; expected off, warn, sanitize, review, or block");
        };
    }
}
