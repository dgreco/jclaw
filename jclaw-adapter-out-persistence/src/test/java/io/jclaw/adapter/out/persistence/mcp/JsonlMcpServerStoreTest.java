// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.mcp;

import io.jclaw.ports.mcp.McpServerStore;
import io.jclaw.ports.mcp.McpServerStore.McpServer;
import io.jclaw.adapter.out.persistence.jsonl.JsonlFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP server store, which is an append-only log replayed into current state.
 *
 * <p>Two things here are worth a test rather than a reading. The log is replayed rather than
 * mutated, so "enabled" and "removed" are later rows that must win over the row that added the
 * server — a replay that took the first row would resurrect a deleted server. And the row holds
 * the *name* of a vault entry and never its value, which is the invariant that keeps a credential
 * out of every store in this tree; a test asserting on the file's bytes is what makes that
 * checkable rather than merely intended.
 */
class JsonlMcpServerStoreTest {

    @TempDir
    Path dir;

    private JsonlMcpServerStore store() {
        return new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp.jsonl")));
    }

    private String rawFile() throws IOException {
        Path file = dir.resolve("mcp.jsonl");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static McpServer stdio(String name) {
        return new McpServer(name, List.of("npx", "some-server"), Map.of("TOKEN", "vault-entry"), true);
    }

    @Test
    @DisplayName("a server round-trips, and an unknown name is absent rather than empty-shaped")
    void addAndFind() {
        var store = store();
        store.add(stdio("files"));

        McpServer found = store.find("files").orElseThrow();
        assertEquals(List.of("npx", "some-server"), found.command());
        assertEquals(Map.of("TOKEN", "vault-entry"), found.envSecrets());
        assertTrue(found.enabled());
        assertEquals(Optional.empty(), store.find("never-added"));
    }

    @Test
    @DisplayName("the log is replayed, so a later row wins over the one that added the server")
    void laterRowsWin() {
        var store = store();
        store.add(stdio("files"));

        assertTrue(store.setEnabled("files", false));
        assertFalse(store.find("files").orElseThrow().enabled(),
                "a replay that took the first row would keep serving a disabled server");

        assertTrue(store.setEnabled("files", true));
        assertTrue(store.find("files").orElseThrow().enabled());

        assertTrue(store.remove("files"));
        assertEquals(Optional.empty(), store.find("files"),
                "a removed server must not come back when the log is replayed");
    }

    @Test
    @DisplayName("enabling or removing something that was never added changes nothing")
    void unknownNamesAreRefused() {
        var store = store();
        assertFalse(store.setEnabled("ghost", true));
        assertFalse(store.remove("ghost"));
        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("list is sorted by name, so two runs of `mcp list` agree")
    void listIsOrdered() {
        var store = store();
        store.add(stdio("zulu"));
        store.add(stdio("alpha"));
        store.add(stdio("mike"));

        assertEquals(List.of("alpha", "mike", "zulu"), store.list().stream().map(McpServer::name).toList());
    }

    @Test
    @DisplayName("state survives a new store over the same file: the log is the state")
    void survivesReopening() {
        store().add(stdio("files"));
        store().setEnabled("files", false);

        assertFalse(store().find("files").orElseThrow().enabled());
    }

    @Test
    @DisplayName("an OAuth client is stored by the name of its vault entry, never by its value")
    void oauthStoresNamesNotValues() throws IOException {
        var store = store();
        store.add(new McpServer("remote", List.of(), Map.of(),
                "https://mcp.example.com/mcp", "",
                Optional.of(new McpServerStore.OAuth("jclaw-client", "mcp-client-secret",
                        "https://mcp.example.com/oauth/token", "mcp.read")),
                true));

        McpServer found = store.find("remote").orElseThrow();
        McpServerStore.OAuth oauth = found.oauth().orElseThrow();
        assertEquals("jclaw-client", oauth.clientId());
        assertEquals("mcp-client-secret", oauth.clientSecretName());
        assertEquals("mcp.read", oauth.scope());

        // The bytes on disk are the assertion that matters: what is written is which vault entry
        // to lease, and a value would have to be leased at connect time to appear at all.
        String raw = rawFile();
        assertTrue(raw.contains("mcp-client-secret"), "the entry's name is what identifies it");
        assertFalse(raw.contains("password") || raw.contains("secretValue"),
                "no credential value belongs in a store: " + raw);
    }

    @Test
    @DisplayName("a row the codec cannot read is skipped, not fatal")
    void malformedRowsAreSkipped() throws IOException {
        Path file = dir.resolve("mcp.jsonl");
        Files.writeString(file, "{\"kind\":\"added\",\"name\":\"broken\"}\n", StandardCharsets.UTF_8);
        var store = store();
        store.add(stdio("intact"));

        assertEquals(List.of("intact"), store.list().stream().map(McpServer::name).toList(),
                "one unreadable row must not take the whole surface down");
    }

    @Test
    @DisplayName("the file is required, and so is the server being added")
    void nullsRefused() {
        assertThrows(NullPointerException.class, () -> new JsonlMcpServerStore(null));
        assertThrows(NullPointerException.class, () -> store().add(null));
    }
}
