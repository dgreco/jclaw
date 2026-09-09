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

        @Parameters(arity = "1..*",
                description = "Command and arguments to launch the server, e.g. npx -y @some/mcp-server")
        private String[] command;

        public Add(McpServerStore store) {
            this.store = store;
        }

        @Override
        public Integer call() {
            if (store.find(name).isPresent()) {
                System.err.println("jclaw: a server named '" + name + "' already exists");
                return 1;
            }
            store.add(new McpServerStore.McpServer(name, List.of(command), Map.of(), true));
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
                        server.commandLine());
            }
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

        public Test(McpServerStore store, WorkspaceGuard workspace, JclawProperties properties) {
            this.store = store;
            this.workspace = workspace;
            this.properties = properties;
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
            return McpClient.start(server.name(), server.command(), server.env(), workspace.root(),
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
