// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.mcp;

import io.jclaw.adapter.out.persistence.rows.RowStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What each MCP server offered the last time it was asked.
 *
 * <p>The capability surface has to be known before a turn starts, because it goes into the
 * prompt. Discovering it means a handshake, and a handshake means starting the server — which is
 * why every CLI invocation used to spawn every configured server before doing anything. Caching
 * the answer breaks that: the surface is published from here, and the server is started only when
 * a capability is actually invoked.
 *
 * <p>An entry is keyed by the server's name <em>and</em> a fingerprint of its configuration, so
 * changing a command, a URL, or an environment variable invalidates it rather than serving a
 * surface the new configuration may not have. {@code jclaw mcp refresh} drops entries on demand.
 */
public final class McpSurfaceCache {

    private static final String KIND_DISCOVERED = "discovered";
    private static final String KIND_DROPPED = "dropped";

    /** One server's cached surface: what it declared, and the tools it listed. */
    public record Surface(String fingerprint, Set<String> offers, List<Map<String, Object>> tools, String at) {
        public Surface {
            Objects.requireNonNull(fingerprint, "fingerprint");
            offers = Set.copyOf(Objects.requireNonNull(offers, "offers"));
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            Objects.requireNonNull(at, "at");
        }
    }

    private final RowStore rows;
    private final Clock clock;

    public McpSurfaceCache(RowStore rows, Clock clock) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The cached surface for a server, if one was stored for exactly this configuration. */
    public Optional<Surface> find(String name, String fingerprint) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return Optional.ofNullable(replay().get(name)).filter(s -> s.fingerprint().equals(fingerprint));
    }

    /** Records what a server offered. Replaces any earlier entry for that name. */
    public void put(String name, String fingerprint, Set<String> offers, List<Map<String, Object>> tools) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_DISCOVERED);
        row.put("name", Objects.requireNonNull(name, "name"));
        row.put("fingerprint", Objects.requireNonNull(fingerprint, "fingerprint"));
        row.put("offers", new ArrayList<>(new LinkedHashSet<>(Objects.requireNonNull(offers, "offers"))));
        row.put("tools", List.copyOf(Objects.requireNonNull(tools, "tools")));
        row.put("at", clock.instant().toString());
        rows.append(row);
    }

    /** Forgets one server's surface, so the next start rediscovers it. Returns whether one was held. */
    public boolean drop(String name) {
        Objects.requireNonNull(name, "name");
        if (!replay().containsKey(name)) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_DROPPED);
        row.put("name", name);
        row.put("at", clock.instant().toString());
        rows.append(row);
        return true;
    }

    /** Every cached server name. */
    public Set<String> names() {
        return Set.copyOf(replay().keySet());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Surface> replay() {
        Map<String, Surface> live = new LinkedHashMap<>();
        for (Map<String, Object> row : rows.readAll()) {
            String name = String.valueOf(row.get("name"));
            switch (String.valueOf(row.get("kind"))) {
                case KIND_DISCOVERED -> {
                    try {
                        Set<String> offers = new LinkedHashSet<>();
                        if (row.get("offers") instanceof List<?> declared) {
                            declared.forEach(offer -> offers.add(String.valueOf(offer)));
                        }
                        List<Map<String, Object>> tools = new ArrayList<>();
                        if (row.get("tools") instanceof List<?> listed) {
                            for (Object tool : listed) {
                                if (tool instanceof Map<?, ?> map) {
                                    tools.add((Map<String, Object>) map);
                                }
                            }
                        }
                        live.put(name, new Surface(String.valueOf(row.get("fingerprint")), offers, tools,
                                String.valueOf(row.get("at"))));
                    } catch (RuntimeException e) {
                        // A damaged row means one rediscovery, not a broken configuration.
                    }
                }
                case KIND_DROPPED -> live.remove(name);
                default -> { }
            }
        }
        return live;
    }
}
