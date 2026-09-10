// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.extension;

import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.TrustClass;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Installed extensions: packages that add a skill or an MCP tool server, with a manifest that
 * declares what they are and what they need.
 *
 * <p>A package is a directory holding {@code jclaw-extension.json} and either a {@code SKILL.md}
 * or the MCP server it launches. The manifest declares the kind, the command, the environment
 * names the server needs (never values), the hosts it says it reaches, the effect class its
 * tools claim, and optionally a publisher. A package may carry {@code jclaw-extension.sig}: an
 * Ed25519 signature over the package digest by the named publisher. When the publisher's key is
 * one the operator trusts, the extension installs as {@link TrustClass#VERIFIED} and its declared
 * effect class is believed; otherwise it is {@link TrustClass#COMMUNITY}, its tools are
 * {@link EffectClass#NETWORK}, and every call gates.
 */
public interface ExtensionRegistry {

    /** What an extension contributes. */
    enum Kind { SKILL, MCP, WASM }

    /**
     * The manifest, as declared by the package.
     *
     * @param env      environment variable names an MCP server requires; values are supplied at
     *                 install time and stored with the installation
     * @param permissions host functions a WASM module may import: {@code log}, {@code read_file},
 *                    {@code http_get}. A module importing one it was not granted does not run
 * @param tools    tool names a WASM module offers, each registered as {@code wasm.<name>.<tool>}
 * @param hosts    hosts the extension declares it reaches; informational, shown at install
     * @param effect   the effect class the extension claims for its tools; honoured only when
     *                 the installation is {@code VERIFIED}
     */
    record Manifest(
            String name,
            String version,
            String description,
            Kind kind,
            List<String> command,
            List<String> env,
            List<String> hosts,
            List<String> permissions,
            List<String> tools,
            EffectClass effect,
            Optional<String> publisher) {

        private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

        public Manifest {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(kind, "kind");
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            env = List.copyOf(Objects.requireNonNull(env, "env"));
            hosts = List.copyOf(Objects.requireNonNull(hosts, "hosts"));
            permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(publisher, "publisher");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("extension name must be 1-64 lower-case letters, digits, '_', '-'");
            }
            if (version.isBlank()) {
                throw new IllegalArgumentException("extension version must not be blank");
            }
            if (kind == Kind.MCP && command.isEmpty()) {
                throw new IllegalArgumentException("an mcp extension must declare a command");
            }
            if (kind == Kind.WASM && tools.isEmpty()) {
                throw new IllegalArgumentException("a wasm extension must declare at least one tool");
            }
            for (String variable : env) {
                if (variable.isBlank() || variable.indexOf('=') >= 0) {
                    throw new IllegalArgumentException("not an environment variable name: '" + variable + "'");
                }
            }
        }
    }

    /** One installation. */
    record Installed(
            Manifest manifest,
            TrustClass trust,
            String digest,
            Map<String, String> secrets,
            boolean enabled,
            Instant installedAt) {

        public Installed {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(trust, "trust");
            Objects.requireNonNull(digest, "digest");
            secrets = Map.copyOf(Objects.requireNonNull(secrets, "secrets"));
            Objects.requireNonNull(installedAt, "installedAt");
        }

        public String name() {
            return manifest.name();
        }

        /** The effect class the extension's tools carry: as declared when verified, else NETWORK. */
        public EffectClass effectiveEffect() {
            return trust == TrustClass.VERIFIED ? manifest.effect() : EffectClass.NETWORK;
        }
    }

    /**
     * Installs the package at {@code packageDir}, replacing any installation of the same name.
     *
     * @param secrets vault secret name for each environment name the manifest requires. Names,
     *                not values: an extension's credential lives in the vault, and this records
     *                only which entry to lease when its server starts
     * @return the installation, or a reason it was refused: an unreadable or invalid manifest, a
     *         signature that does not verify, or a required variable with no secret named for it
     */
    io.jclaw.contracts.Result<Installed, String> install(Path packageDir, Map<String, String> secrets);

    List<Installed> list();

    Optional<Installed> find(String name);

    boolean remove(String name);

    boolean setEnabled(String name, boolean enabled);
}
