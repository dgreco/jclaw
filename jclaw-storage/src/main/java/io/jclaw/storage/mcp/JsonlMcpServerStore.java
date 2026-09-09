package io.jclaw.storage.mcp;

import io.jclaw.contracts.mcp.McpServerStore;
import io.jclaw.storage.rows.RowStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable {@link McpServerStore} over an append-only JSONL file. */
public final class JsonlMcpServerStore implements McpServerStore {

    private static final String KIND_ADDED = "added";
    private static final String KIND_ENABLED = "enabled";
    private static final String KIND_REMOVED = "removed";

    private final RowStore file;

    public JsonlMcpServerStore(RowStore file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public void add(McpServer server) {
        Objects.requireNonNull(server, "server");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_ADDED);
        row.put("name", server.name());
        row.put("command", server.command());
        row.put("envSecrets", server.envSecrets());
        row.put("url", server.url());
        row.put("authSecret", server.authSecret());
        row.put("enabled", server.enabled());
        file.append(row);
    }

    @Override
    public Optional<McpServer> find(String name) {
        return Optional.ofNullable(replay().get(name));
    }

    @Override
    public List<McpServer> list() {
        return replay().values().stream()
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
    }

    @Override
    public boolean setEnabled(String name, boolean enabled) {
        if (find(name).isEmpty()) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_ENABLED);
        row.put("name", name);
        row.put("enabled", enabled);
        file.append(row);
        return true;
    }

    @Override
    public boolean remove(String name) {
        if (find(name).isEmpty()) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_REMOVED);
        row.put("name", name);
        file.append(row);
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, McpServer> replay() {
        Map<String, McpServer> servers = new LinkedHashMap<>();
        for (Map<String, Object> row : file.readAll()) {
            try {
                String kind = String.valueOf(row.get("kind"));
                String name = String.valueOf(row.get("name"));
                switch (kind) {
                    case KIND_ADDED -> servers.put(name, new McpServer(
                            name,
                            row.get("command") instanceof List<?> cmd
                                    ? cmd.stream().map(String::valueOf).toList()
                                    : List.of(),
                            row.get("envSecrets") instanceof Map<?, ?> secrets
                                    ? (Map<String, String>) secrets
                                    : Map.of(),
                            row.get("url") instanceof String url ? url : "",
                            row.get("authSecret") instanceof String secret ? secret : "",
                            !(row.get("enabled") instanceof Boolean flag) || flag));
                    case KIND_REMOVED -> servers.remove(name);
                    case KIND_ENABLED -> {
                        McpServer existing = servers.get(name);
                        if (existing != null) {
                            servers.put(name, existing.withEnabled(
                                    row.get("enabled") instanceof Boolean flag && flag));
                        }
                    }
                    default -> { /* forward compatibility */ }
                }
            } catch (RuntimeException e) {
                // A damaged line loses one server, not the configuration.
            }
        }
        return servers;
    }
}
