// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.secret;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Handing a credential to a child process, without it ever appearing in an argument.
 *
 * <p>{@link SecretInjection} substitutes a value into the arguments a lane receives, which is
 * right for an HTTP header and wrong for a subprocess: an argument becomes a command line, and a
 * command line is world-readable in {@code ps}. Staging is the other handoff. The model names a
 * variable and a secret — never a value, never a reference that expands in place — and the kernel
 * puts the value into the child's environment and nowhere else. The arguments the model wrote are
 * the arguments that get fingerprinted, approved, checkpointed, and logged, and they contain a
 * secret's <em>name</em>, which is public.
 *
 * <p>The two handoffs are deliberately disjoint, and the binding is what separates them. A URL
 * secret is bound to the hosts it may be sent to; {@link Binding#permitsHost} answers for those.
 * A subprocess secret is bound to the single reserved host {@value #SUBPROCESS}, which
 * {@code permitsHost} matches against nothing — so a secret an operator staged for {@code gh} can
 * never be injected into a URL, and a secret bound to {@code api.github.com} can never be staged
 * into a shell command that would post it elsewhere. Neither is a check that could be forgotten;
 * each is the absence of a match.
 *
 * <p>Naming the reserved host {@code *} rather than something readable is not decoration: a
 * binding's hosts must already be host-shaped (no slashes, no colons), and {@code *} is the one
 * such string that cannot be a real host, so an operator cannot bind a subprocess secret by
 * accident and cannot collide with a domain they actually own.
 */
public final class SecretStaging {

    /** The reserved binding host meaning "not a URL target at all — a subprocess environment". */
    public static final String SUBPROCESS = Binding.SUBPROCESS;

    /** POSIX-ish: what a shell will actually pass through to a child. */
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** The argument a capability reads staging requests from. Holds names, never values. */
    public static final String ARGUMENT = "secret_env";

    private SecretStaging() {
    }

    /** Whether a binding may be staged into a subprocess environment for {@code capability}. */
    public static Optional<String> refuse(Binding binding, CapabilityId capability) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(capability, "capability");
        if (!binding.capability().equals(capability)) {
            return Optional.of("secret_not_bound_to_capability");
        }
        if (!binding.hosts().equals(Set.of(SUBPROCESS))) {
            // A secret bound to real hosts was scoped to where it may be *sent*. A subprocess can
            // send it anywhere, so honouring that binding here would quietly discard it.
            return Optional.of("secret_not_bound_for_subprocess");
        }
        return Optional.empty();
    }

    /**
     * Reads a staging request: {@code {"GH_TOKEN": "gh-cli"}} — variable to secret name.
     *
     * @return the requested variables, or a reason the request is malformed. An absent or empty
     *         argument is a request for nothing, which is not an error.
     */
    public static Result<Map<String, SecretName>, String> requested(Object argument) {
        Map<String, SecretName> wanted = new LinkedHashMap<>();
        if (argument == null) {
            return Result.ok(wanted);
        }
        if (!(argument instanceof Map<?, ?> map)) {
            return Result.err("secret_env_must_be_an_object");
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String variable = String.valueOf(entry.getKey());
            if (!VARIABLE.matcher(variable).matches()) {
                return Result.err("secret_env_variable_invalid");
            }
            String name = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            SecretName secret;
            try {
                secret = new SecretName(name);
            } catch (IllegalArgumentException e) {
                return Result.err("secret_env_name_invalid");
            }
            wanted.put(variable, secret);
        }
        return Result.ok(wanted);
    }
}
