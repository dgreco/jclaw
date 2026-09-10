// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.kernel.capability;

/**
 * What the kernel does with tool output that looks like an injection attempt.
 *
 * <p>Ordered from least to most intrusive. None of them is a substitute for the authority gate,
 * which is what actually stops an injected instruction from becoming an effect; these decide how
 * much the model and the operator are told, and whether the content reaches the model at all.
 */
public enum InjectionPolicy {

    /** No scanning. For corpora where the heuristics are known to misfire. */
    OFF,

    /** Scan and record an event; the model sees the output unchanged. */
    WARN,

    /**
     * Scan, record an event, and hand the model a framed copy: chat-template tokens defused, the
     * content fenced, and a notice saying it is data. The default.
     */
    SANITIZE,

    /**
     * As {@link #SANITIZE}, except that {@code HIGH}-severity findings withhold the output from the
     * model entirely: the tool result becomes a denial naming the reason. The full payload is
     * still stored for the operator.
     */
    BLOCK;

    public static InjectionPolicy parse(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> OFF;
            case "warn" -> WARN;
            case "sanitize", "sanitise" -> SANITIZE;
            case "block" -> BLOCK;
            default -> throw new IllegalArgumentException(
                    "unknown injection policy '" + value + "'; expected off, warn, sanitize, or block");
        };
    }
}
