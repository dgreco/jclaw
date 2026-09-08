package io.jclaw.contracts.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Configured MCP servers. */
public interface McpServerStore {

    /**
     * One configured server.
     *
     * @param command the process to run, argv-style. Stored as a list rather than a shell string
     *                so nothing is ever passed through a shell — a server name containing
     *                {@code ; rm -rf} must be inert, not clever.
     * @param env     extra environment for the child, on top of a scrubbed allowlist
     */
    record McpServer(String name, List<String> command, Map<String, String> env, boolean enabled) {

        public McpServer {
            Objects.requireNonNull(name, "name");
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            env = Map.copyOf(Objects.requireNonNull(env, "env"));
            if (name.isBlank()) {
                throw new IllegalArgumentException("server name must not be blank");
            }
            if (command.isEmpty()) {
                throw new IllegalArgumentException("server command must not be empty");
            }
        }

        public McpServer withEnabled(boolean enabled) {
            return new McpServer(name, command, env, enabled);
        }

        /** Display form. Never re-parsed — the argv list is authoritative. */
        public String commandLine() {
            return String.join(" ", command);
        }
    }

    void add(McpServer server);

    Optional<McpServer> find(String name);

    List<McpServer> list();

    boolean setEnabled(String name, boolean enabled);

    boolean remove(String name);
}
