// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.secret;

import io.jclaw.ports.capability.CapabilityId;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Credentials the host holds on the agent's behalf, and the rules for handing one to a tool.
 *
 * <p>The model never sees a secret's value. It refers to one by name, as
 * {@code {{secret:NAME}}} inside a tool argument, and the kernel substitutes the value at the
 * moment of dispatch, after every authority check has passed and only if the secret's
 * {@link Binding} permits that capability and those hosts. The invocation the model wrote, the
 * fingerprint, the approval prompt, the stored result, and every event keep the reference form,
 * so a secret leaves the vault for one handler call and nowhere else.
 *
 * <p>Tool lanes and providers may not hold this port. The dependency-law tests enforce that: the
 * whole point is that a lane which could look secrets up would be a lane worth attacking.
 */
public interface SecretVault {

    /** A vault entry's name: what the model writes inside {@code {{secret:...}}}. */
    record SecretName(String value) {
        private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

        public SecretName {
            Objects.requireNonNull(value, "value");
            if (!SHAPE.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "secret name must be 1-64 characters of letters, digits, '_', '.', '-'");
            }
        }
    }

    /**
     * Where a secret may go.
     *
     * <p>A secret is injected only into the one capability it is bound to, and only when every
     * URL among the invocation's arguments points at one of the bound hosts. Without the host
     * rule the model could send the credential anywhere, which is exfiltration with one tool
     * call; with it, a secret for {@code api.github.com} cannot be posted to a pastebin.
     *
     * @param hosts exact host names, or {@code *.suffix} patterns; never empty
     */
    record Binding(CapabilityId capability, Set<String> hosts) {

        /**
         * The reserved host meaning "not a URL target at all".
         *
         * <p>A secret bound to it is for a subprocess environment, where there is no host to
         * check because a child process can reach anything. {@link #permitsHost} therefore never
         * matches it — including against a URL whose authority is literally {@code *}, which is
         * a string a model can write and a manual URL parser will happily hand back. Without that
         * exclusion the two handoffs would meet: a credential an operator staged for {@code gh}
         * could be substituted into an argument, and an argument becomes a command line.
         */
        public static final String SUBPROCESS = "*";

        public Binding {
            Objects.requireNonNull(capability, "capability");
            hosts = Set.copyOf(Objects.requireNonNull(hosts, "hosts"));
            if (hosts.isEmpty()) {
                throw new IllegalArgumentException("a secret must be bound to at least one host");
            }
            for (String host : hosts) {
                if (host.isBlank() || host.contains("/") || host.contains(":")) {
                    throw new IllegalArgumentException("not a host name: '" + host + "'");
                }
            }
        }

        /** Whether {@code host} matches one of the bound names or suffix patterns. */
        public boolean permitsHost(String host) {
            Objects.requireNonNull(host, "host");
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.equals(SUBPROCESS)) {
                return false;
            }
            for (String bound : hosts) {
                String candidate = bound.toLowerCase(Locale.ROOT);
                if (candidate.equals(SUBPROCESS)) {
                    continue;
                }
                if (candidate.startsWith("*.")) {
                    if (lower.endsWith(candidate.substring(1)) || lower.equals(candidate.substring(2))) {
                        return true;
                    }
                } else if (lower.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Everything about a secret except its value. */
    record SecretInfo(SecretName name, Binding binding, Instant createdAt) {
        public SecretInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * A secret's value, released for one handoff.
     *
     * <p>Deliberately not a record: no {@code toString} that prints the value, no accidental
     * equality on it.
     */
    final class Lease {
        private final SecretInfo info;
        private final String value;

        public Lease(SecretInfo info, String value) {
            this.info = Objects.requireNonNull(info, "info");
            this.value = Objects.requireNonNull(value, "value");
        }

        public SecretInfo info() {
            return info;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return "Lease[" + info.name().value() + "]";
        }
    }

    /** Stores or replaces a secret. */
    void put(SecretName name, String value, Binding binding);

    /** Removes a secret. Returns whether it existed. */
    boolean remove(SecretName name);

    /** Every secret's metadata, by name. Values are never listed. */
    List<SecretInfo> list();

    /** Releases a secret's value for one handoff, or empty when the name is unknown. */
    Optional<Lease> lease(SecretName name);

    /** A vault holding nothing, for hosts configured without one. */
    static SecretVault empty() {
        return new SecretVault() {
            @Override
            public void put(SecretName name, String value, Binding binding) {
                throw new UnsupportedOperationException("no vault is configured");
            }

            @Override
            public boolean remove(SecretName name) {
                return false;
            }

            @Override
            public List<SecretInfo> list() {
                return List.of();
            }

            @Override
            public Optional<Lease> lease(SecretName name) {
                return Optional.empty();
            }
        };
    }
}
