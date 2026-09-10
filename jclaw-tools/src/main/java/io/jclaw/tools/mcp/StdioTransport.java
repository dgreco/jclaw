// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools.mcp;

import io.jclaw.contracts.Result;
import io.jclaw.domain.sandbox.SandboxSpec;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A server run as a child process, speaking newline-delimited JSON-RPC over stdio.
 *
 * <p>The original MCP transport and still the common one. The child gets an allowlisted
 * environment plus whatever the server was configured with, its stderr is discarded rather than
 * parsed (server diagnostics must never be mistaken for protocol), and closing destroys the whole
 * process tree so a server that spawned helpers cannot leak them.
 *
 * <p>With a {@link SandboxSpec} the process jclaw spawns is the Docker client and the server runs
 * in the container it starts; the container's stdio is the server's, so nothing above changes.
 */
public final class StdioTransport implements McpTransport {

    /** Environment a server process may inherit. Same discipline as the shell lane. */
    private static final Set<String> ENV_ALLOWLIST =
            Set.of("PATH", "HOME", "LANG", "LC_ALL", "TZ", "TERM", "USER", "TMPDIR");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Process process;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;
    private final String description;

    private StdioTransport(Process process, String description) {
        this.process = process;
        this.description = description;
        this.toServer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Spawns the server, optionally inside a container. */
    public static Result<McpTransport, String> start(
            List<String> command, Map<String, String> extraEnv, Path workingDirectory,
            Optional<SandboxSpec> sandbox) {

        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(extraEnv, "extraEnv");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(sandbox, "sandbox");
        if (command.isEmpty()) {
            return Result.err("empty_command");
        }

        List<String> argv = sandbox
                .map(spec -> spec.argv(workingDirectory, command, extraEnv.keySet()))
                .orElse(command);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workingDirectory.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> !ENV_ALLOWLIST.contains(key));
        environment.putAll(extraEnv);

        try {
            return Result.ok(new StdioTransport(builder.start(),
                    sandbox.map(spec -> "stdio in " + spec.image()).orElse("stdio")));
        } catch (IOException e) {
            return Result.err("server_spawn_failed");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>, String> send(
            Map<String, Object> envelope, Optional<Long> expectId, Duration timeout) {

        try {
            toServer.write(mapper.writeValueAsString(envelope));
            toServer.newLine();
            toServer.flush();
        } catch (IOException | RuntimeException e) {
            return Result.err("server_write_failed");
        }
        if (expectId.isEmpty()) {
            return Result.ok(Map.of());
        }

        long id = expectId.get();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String line;
            try {
                line = fromServer.readLine();
            } catch (IOException e) {
                return Result.err("server_read_failed");
            }
            if (line == null) {
                return Result.err("server_closed_stream");
            }
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> message;
            try {
                message = mapper.readValue(line, Map.class);
            } catch (RuntimeException e) {
                continue; // not protocol; ignore rather than abort
            }
            Result<Map<String, Object>, String> matched = McpProtocol.matchResponse(message, id);
            if (matched != null) {
                return matched;
            }
        }
        return Result.err("server_timeout");
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public void close() {
        // Destroy the whole tree: an MCP server that spawned helpers would otherwise leak them.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
