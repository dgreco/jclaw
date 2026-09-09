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
    /**
     * OAuth 2.1 client credentials for a remote server.
     *
     * <p>The client-credentials grant, not the authorization-code flow the MCP specification
     * describes for interactive clients. An agent is not a browser: there is nobody at the
     * keyboard to consent, and a headless process that parked a run waiting for one would be
     * worse than a static token. What this buys over a static token is real — the credential the
     * operator holds is exchanged for a short-lived one, and a leaked access token expires — so
     * it is worth having even without the interactive half.
     *
     * @param clientId         the OAuth client id; not a secret, so it may sit in configuration
     * @param clientSecretName the <em>vault entry</em> holding the client secret, bound to
     *                         {@code mcp.connect} and the token endpoint's host
     * @param tokenUrl         the token endpoint, or blank to discover it from the server's
     *                         {@code /.well-known/oauth-authorization-server}
     * @param scope            requested scope, or blank for none
     */
    record OAuth(String clientId, String clientSecretName, String tokenUrl, String scope) {
        public OAuth {
            clientId = Objects.requireNonNull(clientId, "clientId").trim();
            clientSecretName = Objects.requireNonNull(clientSecretName, "clientSecretName").trim();
            tokenUrl = Objects.requireNonNull(tokenUrl, "tokenUrl").trim();
            scope = Objects.requireNonNull(scope, "scope").trim();
            if (clientId.isBlank()) {
                throw new IllegalArgumentException("an oauth client needs a client id");
            }
            if (clientSecretName.isBlank()) {
                throw new IllegalArgumentException("an oauth client needs the name of a vault secret");
            }
        }
    }

    record McpServer(
            String name, List<String> command, Map<String, String> envSecrets,
            String url, String authSecret, Optional<OAuth> oauth, boolean enabled) {

        public McpServer {
            Objects.requireNonNull(name, "name");
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            envSecrets = Map.copyOf(Objects.requireNonNull(envSecrets, "envSecrets"));
            url = Objects.requireNonNull(url, "url").trim();
            authSecret = Objects.requireNonNull(authSecret, "authSecret").trim();
            Objects.requireNonNull(oauth, "oauth");
            if (name.isBlank()) {
                throw new IllegalArgumentException("server name must not be blank");
            }
            if (command.isEmpty() == url.isBlank()) {
                throw new IllegalArgumentException("a server needs either a command or a url, not both");
            }
            if (!url.isBlank() && !command.isEmpty()) {
                throw new IllegalArgumentException("an http server has no command");
            }
            if (oauth.isPresent() && url.isBlank()) {
                throw new IllegalArgumentException("oauth is for an http server; a stdio child has no endpoint");
            }
            if (oauth.isPresent() && !authSecret.isBlank()) {
                throw new IllegalArgumentException(
                        "give either a static --auth-secret or --oauth-client-id, not both");
            }
        }

        /** A stdio server: the common form, and what {@code mcp add} creates without {@code --url}. */
        public McpServer(String name, List<String> command, Map<String, String> envSecrets, boolean enabled) {
            this(name, command, envSecrets, "", "", Optional.empty(), enabled);
        }

        /** An HTTP server with a static bearer token, or none. */
        public McpServer(String name, List<String> command, Map<String, String> envSecrets,
                String url, String authSecret, boolean enabled) {
            this(name, command, envSecrets, url, authSecret, Optional.empty(), enabled);
        }

        public boolean isHttp() {
            return !url.isBlank();
        }

        public McpServer withEnabled(boolean enabled) {
            return new McpServer(name, command, envSecrets, url, authSecret, oauth, enabled);
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
