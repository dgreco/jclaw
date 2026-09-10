// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.secret;

import io.jclaw.contracts.secret.SecretVault.SecretName;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether a credential is about to be sent to a model.
 *
 * <p>The vault's rules govern where a secret <em>goes</em>: which capability, which hosts. They
 * say nothing about what comes back. A tool can return a credential in its output — a config file
 * read from the workspace, an error message from a server that echoed a token, a subprocess that
 * printed its own environment — and that output becomes a message in the next request. The
 * capability boundary is behind us by then; the value is simply text in a transcript heading for
 * a third party.
 *
 * <p>This is the check for that: given the values the vault holds, which of them appear in what
 * is about to be sent. It is a scan for exact substrings, which is the only thing that can be
 * both cheap enough to run on every request and free of false negatives on the case that matters
 * — the value verbatim.
 *
 * <p><strong>Short values are not scanned.</strong> Below {@link #MINIMUM_LENGTH} characters a
 * secret is likely to occur in ordinary prose by coincidence, and a scanner that fires on
 * coincidence is one an operator turns off. The threshold is a deliberate blind spot rather than
 * a bug: a four-character credential is not one this can defend, and pretending otherwise would
 * be worse than saying so.
 *
 * <p>Pure, and it holds no state: the caller supplies the values and decides what to do about a
 * hit. It never returns the value, only which names matched, because the point of the scan is to
 * stop that value from travelling.
 */
public final class SecretLeakScan {

    /** Below this, a value matches by coincidence often enough to be useless as a signal. */
    public static final int MINIMUM_LENGTH = 8;

    private SecretLeakScan() {
    }

    /**
     * The names whose values appear in {@code text}.
     *
     * @param values every secret the vault holds, by name; values shorter than
     *               {@link #MINIMUM_LENGTH} are skipped
     */
    public static Set<SecretName> leaks(String text, Map<SecretName, String> values) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(values, "values");
        Set<SecretName> found = new LinkedHashSet<>();
        if (text.isEmpty()) {
            return found;
        }
        values.forEach((name, value) -> {
            if (value != null && value.length() >= MINIMUM_LENGTH && text.contains(value)) {
                found.add(name);
            }
        });
        return found;
    }

    /**
     * Replaces every occurrence of a scanned value with its reference form.
     *
     * <p>Redacting rather than refusing keeps a run alive when a tool happens to have echoed a
     * credential, and leaves the model something true to read: {@code {{secret:gh}}} is exactly
     * what it would have written to use the secret in the first place.
     */
    public static String redact(String text, Map<SecretName, String> values) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(values, "values");
        String out = text;
        for (Map.Entry<SecretName, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.length() >= MINIMUM_LENGTH) {
                out = out.replace(value, "{{secret:" + entry.getKey().value() + "}}");
            }
        }
        return out;
    }
}
