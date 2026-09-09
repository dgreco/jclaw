package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.kernel.guard.WorkspaceGuard;
import io.jclaw.tools.mcp.McpClient;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Manages MCP server integrations.
 *
 * <p>{@code test} is the one that earns its place: it starts the server, completes the handshake,
 * lists its tools, and shuts it down — answering "is this configured correctly" without waiting to
 * discover the answer during a real turn.
 */
@Component
@Command(
        name = "mcp",
        description = "Add, inspect, and test MCP server integrations.",
        mixinStandardHelpOptions = true,
        subcommands = {
                McpCommand.Refresh.class,
                McpCommand.Add.class,
                McpCommand.ListServers.class,
                McpCommand.Remove.class,
                McpCommand.Toggle.class,
                McpCommand.Test.class
        })
public class McpCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Component
    @Command(name = "add", description = "Register an MCP server.", mixinStandardHelpOptions = true)
    public static class Add implements Callable<Integer> {

        private final McpServerStore store;

        @Option(names = "--name", required = true, description = "Short name for the server.")
        private String name;

        @Parameters(arity = "0..*",
                description = "Command and arguments to launch the server, e.g. npx -y @some/mcp-server")
        private String[] command = new String[0];

        @Option(names = "--url",
                description = "Endpoint of a remote server over streamable HTTP, instead of a command.")
        private String url;

        @Option(names = "--auth-secret",
                description = "With --url: name of a vault secret sent as a bearer token. "
                        + "Bind it to the capability mcp.connect and the endpoint's host.")
        private String authSecret;

        @Option(names = "--secret",
                description = "VAR=secret-name: give the server process VAR from a vault secret. "
                        + "Bind the secret to the capability mcp.connect. Repeatable.")
        private String[] secrets = new String[0];

        public Add(McpServerStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            if (store.find(name).isPresent()) {
                System.err.println("jclaw: a server named '" + name + "' already exists");
                return 1;
            }
            boolean remote = url != null && !url.isBlank();
            if (remote == (command.length > 0)) {
                System.err.println(remote
                        ? "jclaw: an --url server has no command"
                        : "jclaw: give a command to launch, or --url for a remote server");
                return 1;
            }
            if (authSecret != null && !remote) {
                System.err.println("jclaw: --auth-secret applies to --url servers only");
                return 1;
            }
            java.util.Map<String, String> envSecrets = new java.util.LinkedHashMap<>();
            for (String pair : secrets) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    System.err.println("jclaw: --secret expects VAR=secret-name, got '" + pair + "'");
                    return 1;
                }
                envSecrets.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            if (!envSecrets.isEmpty() && remote) {
                System.err.println("jclaw: --secret sets a child process's environment; "
                        + "an --url server authenticates with --auth-secret");
                return 1;
            }
            store.add(new McpServerStore.McpServer(name, remote ? List.of() : List.of(command), envSecrets,
                    remote ? url.trim() : "", authSecret == null ? "" : authSecret.trim(), true));
            System.out.println("Added MCP server '" + name + "'");
            System.out.println("Verify it with: jclaw mcp test " + name);
            System.out.println();
            System.out.println("Note: MCP tools are third-party and always require approval, "
                    + "in every mode.");
            return 0;
        }
    }

    @Component
    @Command(name = "list", description = "List registered servers.", mixinStandardHelpOptions = true)
    public static class ListServers implements Callable<Integer> {

        private final McpServerStore store;

        public ListServers(McpServerStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            List<McpServerStore.McpServer> servers = store.list();
            if (servers.isEmpty()) {
                System.out.println("(no MCP servers configured)");
                return 0;
            }
            for (McpServerStore.McpServer server : servers) {
                System.out.printf("%-20s %-10s %s%n",
                        server.name(),
                        server.enabled() ? "enabled" : "disabled",
                        (server.isHttp() ? "http " : "stdio ") + server.commandLine());
            }
            return 0;
        }
    }

    @Component
    @Command(name = "refresh",
            description = "Forget cached server surfaces, so the next run rediscovers them.",
            mixinStandardHelpOptions = true)
    public static class Refresh implements Callable<Integer> {

        private final io.jclaw.storage.mcp.McpSurfaceCache cache;

        @Parameters(index = "0", arity = "0..1", description = "Server name. Omit for all.")
        private String name;

        public Refresh(io.jclaw.storage.mcp.McpSurfaceCache cache) {
            this.cache = cache;
        }

        @Override
        public Integer call() {
            if (name != null) {
                if (cache.drop(name)) {
                    System.out.println("forgot the cached surface of '" + name + "'");
                    return 0;
                }
                System.err.println("jclaw: nothing cached for '" + name + "'");
                return 1;
            }
            java.util.Set<String> names = cache.names();
            names.forEach(cache::drop);
            System.out.println(names.isEmpty()
                    ? "(nothing cached)"
                    : "forgot " + names.size() + " cached surface(s): " + String.join(", ", new java.util.TreeSet<>(names)));
            return 0;
        }
    }

    @Component
    @Command(name = "remove", description = "Remove a server.", mixinStandardHelpOptions = true)
    public static class Remove implements Callable<Integer> {

        private final McpServerStore store;

        @Parameters(index = "0", description = "Server name.")
        private String name;

        public Remove(McpServerStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            if (!store.remove(name)) {
                System.err.println("jclaw: no such server: " + name);
                return 1;
            }
            System.out.println("Removed " + name);
            return 0;
        }
    }

    @Component
    @Command(name = "toggle", description = "Enable or disable a server.",
            mixinStandardHelpOptions = true)
    public static class Toggle implements Callable<Integer> {

        private final McpServerStore store;

        @Parameters(index = "0", description = "Server name.")
        private String name;

        @Option(names = "--disable", description = "Disable instead of enabling.")
        private boolean disable;

        public Toggle(McpServerStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            if (!store.setEnabled(name, !disable)) {
                System.err.println("jclaw: no such server: " + name);
                return 1;
            }
            System.out.println((disable ? "Disabled " : "Enabled ") + name);
            return 0;
        }
    }

    /** Starts a server, lists its tools, and shuts it down. */
    @Component
    @Command(name = "test", description = "Connect to a server and list the tools it offers.",
            mixinStandardHelpOptions = true)
    public static class Test implements Callable<Integer> {

        private final McpServerStore store;
        private final WorkspaceGuard workspace;

        @Parameters(index = "0", description = "Server name.")
        private String name;

        private final JclawProperties properties;
        private final io.jclaw.contracts.secret.SecretVault vault;

        public Test(McpServerStore store, WorkspaceGuard workspace, JclawProperties properties,
                io.jclaw.contracts.secret.SecretVault vault) {
            this.store = store;
            this.workspace = workspace;
            this.properties = properties;
            this.vault = vault;
        }

        @Override
        public Integer call() {
            return store.find(name)
                    .map(this::probe)
                    .orElseGet(() -> {
                        System.err.println("jclaw: no such server: " + name);
                        return 1;
                    });
        }

        private int probe(McpServerStore.McpServer server) {
            System.out.println("Starting " + server.commandLine() + " ...");
            var environment = io.jclaw.app.runtime.McpCredentials.resolve(
                    server.envSecrets(), java.util.Set.of(), vault);
            if (environment.isErr()) {
                System.err.println("jclaw: cannot start server (" + environment.errorAsOptional().orElse("?") + ")");
                return 1;
            }
            return McpClient.start(server.name(), server.command(), environment.orElseThrow(), workspace.root(),
                            io.jclaw.app.config.JclawConfiguration.mcpSandboxSpec(properties))
                    .fold(
                            client -> {
                                try {
                                    return listTools(client);
                                } finally {
                                    client.close();
                                }
                            },
                            reason -> {
                                System.err.println("jclaw: could not start server (" + reason + ")");
                                return 1;
                            });
        }

        private int listTools(McpClient client) {
            return client.listTools().fold(
                    tools -> {
                        System.out.println("Connected. " + tools.size() + " tool(s):");
                        tools.forEach(tool -> System.out.println(
                                "  mcp." + name + "." + tool.name() + "  " + tool.description()));
                        System.out.println();
                        System.out.println("These register as TrustClass COMMUNITY and always "
                                + "require approval before running.");
                        return 0;
                    },
                    reason -> {
                        System.err.println("jclaw: handshake succeeded but tools/list failed ("
                                + reason + ")");
                        return 1;
                    });
        }
    }
}
