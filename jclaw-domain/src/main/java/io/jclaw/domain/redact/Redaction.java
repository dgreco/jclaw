package io.jclaw.domain.redact;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure redaction of secret-shaped text.
 *
 * <p>Two complementary strategies, because either alone is insufficient:
 *
 * <ul>
 *   <li><b>Known values</b> — literals the host holds (an API key it just leased) are masked
 *       wherever they appear. Exact, no false positives, but only covers what we already know.</li>
 *   <li><b>Shape patterns</b> — text that looks like a credential is masked even when unknown.
 *       Catches the key a subprocess printed that we never held.</li>
 * </ul>
 *
 * <p>This is defence in depth, not a guarantee. Redaction is the last line: the primary control is
 * that secrets are leased for one handoff and never routed into transcripts, events, or tool
 * output in the first place. Anything relying on this function alone is already misdesigned.
 *
 * <p>Deliberately conservative about false positives in one direction only — it would rather mask
 * a harmless high-entropy string than leak a real key.
 */
public final class Redaction {

    /** What replaces a redacted span. Fixed width so output length leaks nothing about the secret. */
    public static final String MASK = "[REDACTED]";

    /** Below this length a "secret" is too short to mask without destroying ordinary text. */
    private static final int MIN_SECRET_LENGTH = 8;

    private static final List<Pattern> SHAPES = List.of(
            // Provider key formats, most specific first.
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("gho_[A-Za-z0-9]{20,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            // Bearer tokens in headers or logs.
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
            // key=value / key: value assignments whose name implies a secret.
            Pattern.compile("(?i)\\b(api[_-]?key|secret|password|passwd|token|credential)"
                    + "\\b\\s*[:=]\\s*[\"']?([^\\s\"',;]{" + MIN_SECRET_LENGTH + ",})[\"']?"),
            // JWTs.
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            // PEM private key blocks.
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"));

    private Redaction() {
    }

    /**
     * Masks known secret values and secret-shaped spans in {@code text}.
     *
     * @param knownSecrets literal values the host knows to be sensitive; may be empty
     */
    public static String redact(String text, Set<String> knownSecrets) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(knownSecrets, "knownSecrets");
        if (text.isEmpty()) {
            return text;
        }

        String result = text;

        // Known values first: longest first, so a token that contains a shorter one does not get
        // half-masked into an unrecognizable fragment.
        List<String> ordered = new ArrayList<>(knownSecrets);
        ordered.removeIf(secret -> secret == null || secret.length() < MIN_SECRET_LENGTH);
        ordered.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String secret : ordered) {
            result = result.replace(secret, MASK);
        }

        for (Pattern pattern : SHAPES) {
            result = maskPattern(pattern, result);
        }
        return result;
    }

    /** Convenience for callers with no known-secret set. */
    public static String redact(String text) {
        return redact(text, Set.of());
    }

    /**
     * Whether {@code text} contains anything this redactor would mask. Useful for asserting in
     * tests that a value is clean before it is persisted.
     */
    public static boolean containsSecret(String text, Set<String> knownSecrets) {
        return !redact(text, knownSecrets).equals(text);
    }

    /**
     * Masks only the sensitive capture group when a pattern defines one, so
     * {@code api_key=abc123...} becomes {@code api_key=[REDACTED]} rather than losing the name
     * that makes the log line useful.
     */
    private static String maskPattern(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder out = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            String replacement;
            if (matcher.groupCount() >= 2 && matcher.group(2) != null) {
                // Keep everything before the secret group, mask only the value.
                String whole = matcher.group();
                String secret = matcher.group(2);
                int secretAt = whole.lastIndexOf(secret);
                replacement = whole.substring(0, secretAt) + MASK;
            } else {
                replacement = MASK;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Truncates to {@code maxChars}, appending a marker when content was dropped. Applied after
     * redaction so a secret straddling the cut cannot survive as a fragment.
     */
    public static String bound(String text, int maxChars) {
        Objects.requireNonNull(text, "text");
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "... [truncated " + (text.length() - maxChars) + " chars]";
    }

    /** Collects the distinct secret-shaped spans found, for diagnostics. Never logged verbatim. */
    public static Set<String> findSecretShapes(String text) {
        Objects.requireNonNull(text, "text");
        Set<String> found = new LinkedHashSet<>();
        for (Pattern pattern : SHAPES) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                found.add(pattern.pattern());
            }
        }
        return found;
    }
}
