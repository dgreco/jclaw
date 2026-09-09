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
    /**
     * A registered server, reached one of two ways.
     *
     * @param command the child process to spawn, for a stdio server; empty for an HTTP one
     * @param envSecrets environment the child process needs, as variable name to <em>vault secret
     *                   name</em>. Values are never stored here: they are leased when the server
     *                   starts, so a credential lives in the vault and nowhere else
     * @param url     the endpoint of a remote server over streamable HTTP; blank for stdio
     * @param authSecret name of a vault secret to send as a bearer token when connecting over
     *                   HTTP; blank for none. The value never lives here
     */
    record McpServer(
            String name, List<String> command, Map<String, String> envSecrets,
            String url, String authSecret, boolean enabled) {

        public McpServer {
            Objects.requireNonNull(name, "name");
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            envSecrets = Map.copyOf(Objects.requireNonNull(envSecrets, "envSecrets"));
            url = Objects.requireNonNull(url, "url").trim();
            authSecret = Objects.requireNonNull(authSecret, "authSecret").trim();
            if (name.isBlank()) {
                throw new IllegalArgumentException("server name must not be blank");
            }
            if (command.isEmpty() == url.isBlank()) {
                throw new IllegalArgumentException("a server needs either a command or a url, not both");
            }
            if (!url.isBlank() && !command.isEmpty()) {
                throw new IllegalArgumentException("an http server has no command");
            }
        }

        /** A stdio server: the common form, and what {@code mcp add} creates without {@code --url}. */
        public McpServer(String name, List<String> command, Map<String, String> envSecrets, boolean enabled) {
            this(name, command, envSecrets, "", "", enabled);
        }

        public boolean isHttp() {
            return !url.isBlank();
        }

        public McpServer withEnabled(boolean enabled) {
            return new McpServer(name, command, envSecrets, url, authSecret, enabled);
        }

        /** How the server is reached, for listings. Never a credential. */
        public String commandLine() {
            return isHttp() ? url : String.join(" ", command);
        }
    }

    void add(McpServer server);

    Optional<McpServer> find(String name);

    List<McpServer> list();

    boolean setEnabled(String name, boolean enabled);

    boolean remove(String name);
}
