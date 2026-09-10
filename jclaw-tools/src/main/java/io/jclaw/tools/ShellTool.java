// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.domain.sandbox.SandboxSpec;
import io.jclaw.domain.secret.SecretStaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs a shell command inside the workspace, on the host or in a container.
 *
 * <p>The most dangerous capability in the harness, and deliberately the least clever. It does
 * <em>not</em> try to parse commands, detect dangerous ones, or maintain a denylist of binaries —
 * that arms race is unwinnable, and a partial denylist is worse than none because it implies a
 * safety that is not there. Instead the containment is structural:
 *
 * <ul>
 *   <li>declared {@link EffectClass#PROCESS}, so under the default policy it always raises an
 *       approval gate showing the exact command;</li>
 *   <li>the working directory is the workspace root;</li>
 *   <li>the environment is scrubbed — the child inherits a minimal allowlist, so an
 *       {@code ANTHROPIC_API_KEY} in the parent process is not readable by anything it spawns;</li>
 *   <li>a hard timeout, with the process tree destroyed on expiry;</li>
 *   <li>bounded output.</li>
 * </ul>
 *
 * <p>With a {@link SandboxSpec} the command additionally runs inside a container: no network,
 * the workspace as the only mount, memory, CPU, and pid limits, a read-only root. That is the
 * process backend IronClaw's sandbox lane selects by policy; here it is selected by
 * configuration, and the host backend remains for machines without a container runtime. The
 * timeout, environment scrub, and output bound apply to both, since the container runtime is
 * itself a host process.
 *
 * <p>A command may be given credentials by name through {@code secret_env}: the kernel puts the
 * values into the child's environment and never into the command line, so {@code ps} shows the
 * command and nothing else. Each such secret must be bound to this capability for subprocess
 * use, which is an operator saying that arbitrary shell code may hold it — a subprocess can
 * send a value anywhere, so a host binding would be a promise this lane cannot keep.
 *
 * <p>The environment scrub matters more than it looks. Without it, {@code env} is a credential
 * exfiltration tool and every other guard is decoration.
 */
public final class ShellTool implements CapabilityHandler {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    /**
     * Environment variables a child process may inherit. Everything else is dropped, including
     * every credential the harness itself uses.
     */
    private static final Set<String> ENV_ALLOWLIST = Set.of(
            "PATH", "HOME", "LANG", "LC_ALL", "TZ", "TERM", "SHELL", "USER", "TMPDIR");

    private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
            "shell",
            "Run a shell command in the workspace directory and return its combined output. "
                    + "The environment is scrubbed and the command runs under a timeout.",
            EffectClass.PROCESS,
            Schemas.object(
                    Schemas.properties(
                            "command", Schemas.string("Shell command to execute."),
                            "timeoutSeconds", Schemas.integer(
                                    "Seconds before the command is killed.", 1, MAX_TIMEOUT_SECONDS),
                            SecretStaging.ARGUMENT, Schemas.stringMap(
                                    "Credentials to place in the command's environment, as "
                                            + "variable name to vault secret name. Names only: the "
                                            + "value never appears in the command line. Each secret "
                                            + "must be bound to builtin.shell for subprocess use.")),
                    List.of("command")));

    private final Optional<SandboxSpec> sandbox;

    /** Host backend: the command runs as a child of this process. */
    public ShellTool() {
        this(Optional.empty());
    }

    /** @param sandbox when present, every command runs in a container described by it */
    public ShellTool(Optional<SandboxSpec> sandbox) {
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    }

    /** Which backend this lane uses, for diagnostics. */
    public String backend() {
        return sandbox.map(spec -> "docker (" + spec.image() + ", network " + spec.network() + ")").orElse("host");
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
        String command = invocation.stringArg("command", "");
        if (command.isBlank()) {
            return Result.err(HandlerError.failed("command_required"));
        }
        int timeoutSeconds = Math.clamp(
                invocation.intArg("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS), 1, MAX_TIMEOUT_SECONDS);

        // Whatever the kernel released for this call, and nothing else. The lane cannot ask.
        Map<String, String> staged = context.stagedEnvironment();
        return context.resolvePath(".").mapErr(HandlerError::denied)
                .flatMap(workdir -> run(command, workdir, timeoutSeconds, staged));
    }

    private Result<String, HandlerError> run(
            String command, Path workdir, int timeoutSeconds, Map<String, String> staged) {
        List<String> argv = sandbox
                .map(spec -> spec.argv(workdir, List.of("/bin/sh", "-c", command), staged.keySet()))
                .orElse(List.of("/bin/sh", "-c", command));
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workdir.toFile());
        builder.redirectErrorStream(true); // interleaved, as a human would see it

        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> !ENV_ALLOWLIST.contains(key));
        // After the scrub, never before: a staged credential is the one thing here that is
        // supposed to reach the child, and clearing it again would be the obvious bug. Under the
        // sandbox the container is told the names and the value travels through this same map,
        // so a credential is never an argument on the `docker run` line either.
        environment.putAll(staged);

        Process process = null;
        try {
            process = builder.start();
            // Close stdin so a command waiting for input fails fast instead of hanging to timeout.
            process.getOutputStream().close();

            String output = readBounded(process.getInputStream());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return Result.err(HandlerError.failed("timed_out"));
            }
            int exitCode = process.exitValue();
            String body = output.isBlank() ? "(no output)" : output;
            return Result.ok(exitCode == 0
                    ? body
                    : "exit status " + exitCode + "\n" + body);

        } catch (IOException e) {
            return Result.err(HandlerError.failed(sandbox.isPresent() ? "sandbox_unavailable" : "spawn_failed"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(HandlerError.failed("interrupted"));
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    /**
     * Reads at most {@link #MAX_OUTPUT_BYTES}. Bounding here rather than after the fact means a
     * command producing unbounded output cannot exhaust memory before anyone truncates it.
     */
    private static String readBounded(InputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        int read;
        while (collected.size() < MAX_OUTPUT_BYTES && (read = stream.read(buffer)) != -1) {
            collected.write(buffer, 0, Math.min(read, MAX_OUTPUT_BYTES - collected.size()));
        }
        return collected.toString(StandardCharsets.UTF_8);
    }
}
