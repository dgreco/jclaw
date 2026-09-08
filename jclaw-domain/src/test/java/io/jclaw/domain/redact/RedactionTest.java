package io.jclaw.domain.redact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionTest {

    @Test
    @DisplayName("masks a known secret value wherever it appears")
    void masksKnownSecret() {
        String secret = "super-secret-value-1234";
        String text = "connecting with " + secret + " and again " + secret;

        String result = Redaction.redact(text, Set.of(secret));

        assertFalse(result.contains(secret), "the raw secret must not survive");
        assertEquals("connecting with [REDACTED] and again [REDACTED]", result);
    }

    @Test
    @DisplayName("masks provider key shapes even when the value is unknown")
    void masksUnknownKeyShapes() {
        // The host never held these; only their shape gives them away.
        String text = """
                anthropic: sk-ant-api03-abcdefghijklmnopqrstuvwxyz123456
                openai: sk-abcdefghijklmnopqrstuvwxyz1234567890
                github: ghp_abcdefghijklmnopqrstuvwxyz1234567890
                aws: AKIAIOSFODNN7EXAMPLE
                """;

        String result = Redaction.redact(text);

        assertFalse(result.contains("sk-ant-api03"), "anthropic key leaked");
        assertFalse(result.contains("ghp_abcdefghij"), "github token leaked");
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"), "aws key leaked");
        assertTrue(result.contains("anthropic:"), "surrounding context should survive");
    }

    @Test
    @DisplayName("keeps the field name but masks the value in key=value pairs")
    void keepsFieldNameMasksValue() {
        String result = Redaction.redact("api_key=abcdefghijklmnop token: zyxwvutsrqponmlk");

        assertTrue(result.contains("api_key="), "the field name is useful and not sensitive");
        assertFalse(result.contains("abcdefghijklmnop"), "the value must be masked");
        assertFalse(result.contains("zyxwvutsrqponmlk"), "the token value must be masked");
    }

    @Test
    @DisplayName("masks bearer tokens, JWTs, and PEM private keys")
    void masksTokensAndKeys() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";

        assertFalse(Redaction.redact("Authorization: Bearer abcdefghijklmnopqrstuvwxyz123")
                .contains("abcdefghijklmnopqrstuvwxyz123"));
        assertFalse(Redaction.redact(jwt).contains("dozjgNryP4J3"));
        assertFalse(Redaction.redact(pem).contains("MIIEowIBAAKCAQEA"));
    }

    @Test
    @DisplayName("longer secrets are masked before shorter ones they contain")
    void longestSecretFirst() {
        // Masking "abc12345" first would leave "678" dangling from the longer secret.
        String shortSecret = "abc12345";
        String longSecret = "abc12345678extra";

        String result = Redaction.redact("value " + longSecret, Set.of(shortSecret, longSecret));

        assertEquals("value [REDACTED]", result);
    }

    @Test
    @DisplayName("leaves ordinary prose untouched")
    void leavesProseAlone() {
        String prose = "The quick brown fox jumps over the lazy dog near the repository root.";

        assertEquals(prose, Redaction.redact(prose));
        assertFalse(Redaction.containsSecret(prose, Set.of()));
    }

    @Test
    @DisplayName("ignores known values too short to mask safely")
    void ignoresShortValues() {
        // Masking "cat" would shred every word containing it.
        String result = Redaction.redact("the cat sat on the mat", Set.of("cat"));

        assertEquals("the cat sat on the mat", result);
    }

    @Test
    @DisplayName("bound truncates and reports how much was dropped")
    void boundTruncates() {
        String result = Redaction.bound("x".repeat(100), 10);

        assertTrue(result.startsWith("xxxxxxxxxx"));
        assertTrue(result.contains("truncated 90 chars"));
    }

    @Test
    @DisplayName("redaction runs before truncation so a straddling secret cannot survive")
    void redactThenBound() {
        String secret = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz123456";
        String text = "prefix " + secret + " suffix";

        // Applied in the documented order: redact first, then bound.
        String result = Redaction.bound(Redaction.redact(text), 20);

        assertFalse(result.contains("sk-ant"), "no fragment of the key may remain");
    }
}
