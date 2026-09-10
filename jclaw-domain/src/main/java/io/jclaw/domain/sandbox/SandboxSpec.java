// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure description of a container sandbox for one process, and the {@code docker run} argument
 * vector that realises it.
 *
 * <p>The shell lane runs commands in it and the MCP lane runs server processes in it; either can
 * instead run on the host. This record is the container's contract, decided by the operator and
 * rendered here without touching a process. Every flag exists for one threat:
 *
 * <ul>
 *   <li>{@code --network none} by default: a command that can reach the network from inside the
 *       agent's process is an exfiltration path, and {@code http_fetch} already exists for
 *       guarded egress;</li>
 *   <li>the workspace is the only mount, at a fixed path, so {@code cat /etc/passwd} reads the
 *       image's file, not the host's;</li>
 *   <li>memory, CPU, and pid limits, so a fork bomb or a runaway build hits the cgroup, not the
 *       machine;</li>
 *   <li>a scrubbed environment: the container starts with none of the host's variables, and only
 *       the few named here are set inside it;</li>
 *   <li>{@code --rm} and a fixed image: nothing persists between commands except the workspace.</li>
 * </ul>
 *
 * <p>The mount point is deliberately {@code /workspace} rather than the host path, so paths the
 * model sees inside the container never reveal the host layout.
 */
public record SandboxSpec(
        String dockerBinary,
        String image,
        String network,
        String memory,
        String cpus,
        int pidsLimit,
        boolean readOnlyRoot) {

    /** Where the workspace is mounted inside the container. */
    public static final String MOUNT = "/workspace";

    public SandboxSpec {
        Objects.requireNonNull(dockerBinary, "dockerBinary");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(cpus, "cpus");
        if (dockerBinary.isBlank() || image.isBlank()) {
            throw new IllegalArgumentException("docker binary and image must be set");
        }
        if (pidsLimit <= 0) {
            throw new IllegalArgumentException("pidsLimit must be positive");
        }
    }

    /** A conservative default: no network, half a gigabyte, one CPU, a small pid budget. */
    public static SandboxSpec defaults(String dockerBinary, String image) {
        return new SandboxSpec(dockerBinary, image, "none", "512m", "1", 256, true);
    }

    /**
     * The argument vector for one command.
     *
     * @param workspace host path mounted read-write at {@link #MOUNT}
     * @param command   the shell command, run by the image's {@code /bin/sh -c}
     */
    public List<String> argv(Path workspace, String command) {
        Objects.requireNonNull(command, "command");
        return argv(workspace, List.of("/bin/sh", "-c", command), Set.of());
    }

    /**
     * The argument vector for one program, such as an MCP server.
     *
     * @param program        the program and its arguments, run as-is inside the container
     * @param passThroughEnv names of variables the container should receive from the Docker
     *                       client's own environment. Passing names rather than values keeps a
     *                       server's API key out of the argument vector, where every process on
     *                       the host could read it
     */
    public List<String> argv(Path workspace, List<String> program, Set<String> passThroughEnv) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(passThroughEnv, "passThroughEnv");
        if (program.isEmpty()) {
            throw new IllegalArgumentException("program must not be empty");
        }
        List<String> argv = new ArrayList<>();
        argv.add(dockerBinary);
        argv.add("run");
        argv.add("--rm");
        argv.add("--interactive");           // stdin is closed by the lane; keeps sh from waiting
        argv.add("--network");
        argv.add(network);
        argv.add("--memory");
        argv.add(memory);
        argv.add("--cpus");
        argv.add(cpus);
        argv.add("--pids-limit");
        argv.add(String.valueOf(pidsLimit));
        if (readOnlyRoot) {
            argv.add("--read-only");
            argv.add("--tmpfs");
            argv.add("/tmp:rw,noexec,nosuid,size=64m");
        }
        argv.add("--volume");
        argv.add(workspace.toAbsolutePath() + ":" + MOUNT + ":rw");
        argv.add("--workdir");
        argv.add(MOUNT);
        argv.add("--env");
        argv.add("HOME=" + MOUNT);
        argv.add("--env");
        argv.add("LANG=C.UTF-8");
        for (String name : new TreeSet<>(passThroughEnv)) {
            if (name.isBlank() || name.indexOf('=') >= 0) {
                throw new IllegalArgumentException("not an environment variable name: '" + name + "'");
            }
            argv.add("--env");
            argv.add(name);
        }
        argv.add(image);
        argv.addAll(program);
        return List.copyOf(argv);
    }
}
