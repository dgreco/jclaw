package io.jclaw.contracts.turn;

import java.util.Objects;
import java.util.UUID;

/**
 * Shared behaviour for the typed identifiers in this package.
 *
 * <p>Every id in jclaw is a distinct type over a string rather than a bare {@code String}.
 * The turn machine juggles half a dozen id kinds at once; making them separate types means a
 * {@code TurnRunId} can never be passed where a {@code ThreadId} belongs, which is exactly the
 * class of mistake that is invisible in review and expensive at runtime.
 */
public interface Ident {

    /** The underlying opaque token. Never parsed for meaning — ids are identities, not data. */
    String value();

    /** Validates an id token: non-blank, no whitespace, bounded length. */
    static String validate(String value, String type) {
        Objects.requireNonNull(value, type);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(type + " must not be blank");
        }
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException(type + " exceeds 200 chars");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                throw new IllegalArgumentException(type + " must not contain whitespace: " + trimmed);
            }
        }
        return trimmed;
    }

    /**
     * Fresh random token. Kept here so id minting is one implementation rather than a
     * {@code UUID.randomUUID()} sprinkled across every store.
     */
    static String fresh(String prefix) {
        return prefix + '_' + UUID.randomUUID().toString().replace("-", "");
    }
}
