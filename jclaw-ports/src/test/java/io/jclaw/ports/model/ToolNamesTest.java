// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoding that keeps dotted capability ids off the wire.
 *
 * <p>This existing at all is the fix for a real bug: every request carrying tools was rejected
 * with a 400 because {@code builtin.read_file} is not a legal function name, and the failure
 * surfaced four layers up as an opaque "internal".
 */
class ToolNamesTest {

    @Test
    @DisplayName("dots become double underscores")
    void encodesDots() {
        assertEquals("builtin_read_file", ToolNames.toWire("builtin.read_file"));
        assertEquals("mcp_github_create_issue", ToolNames.toWire("mcp.github.create_issue"));
    }

    @Test
    @DisplayName("every encoded name matches what model APIs accept")
    void alwaysWireSafe() {
        List<String> ids = List.of(
                "builtin.read_file", "builtin.spawn_subagent", "mcp.demo.reverse",
                "mcp.my_server.some_tool", "builtin.trigger_create");

        for (String id : ids) {
            String wire = ToolNames.toWire(id);
            assertTrue(ToolNames.isWireSafe(wire), id + " encoded to an unsafe name: " + wire);
            assertTrue(wire.length() <= 64, wire + " exceeds the 64 character limit");
        }
    }

    @Test
    @DisplayName("names longer than the limit are shortened but stay distinct")
    void shortensLongNames() {
        String longA = "mcp." + "a".repeat(70) + ".tool_one";
        String longB = "mcp." + "a".repeat(70) + ".tool_two";

        String wireA = ToolNames.toWire(longA);
        String wireB = ToolNames.toWire(longB);

        assertTrue(wireA.length() <= 64);
        assertTrue(ToolNames.isWireSafe(wireA));
        // A shared prefix must not collapse two capabilities into one name.
        assertNotEquals(wireA, wireB, "long names sharing a prefix must stay distinct");
    }

    @Test
    @DisplayName("decoding is a lookup, so an ambiguous encoding cannot mis-resolve")
    void decodesByLookup() {
        Map<String, String> mapping = ToolNames.wireNamesFor(
                List.of("builtin.read_file", "builtin.write_file"));

        assertEquals("builtin.read_file", ToolNames.fromWire("builtin_read_file", mapping));
        assertEquals("builtin.write_file", ToolNames.fromWire("builtin_write_file", mapping));
    }

    @Test
    @DisplayName("an unknown name passes through so the kernel can deny it")
    void unknownNamePassesThrough() {
        Map<String, String> mapping = ToolNames.wireNamesFor(List.of("builtin.read_file"));

        // A hallucinated tool must reach the capability host as an unknown id, not be silently
        // rewritten into something that exists.
        assertEquals("totally__invented", ToolNames.fromWire("totally__invented", mapping));
    }

    @Test
    @DisplayName("a colliding pair is rejected rather than silently shadowing one capability")
    void rejectsCollisions() {
        // Both encode to x_a_b: one would shadow the other, which is an authorization hole
        // rather than a display glitch.
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ToolNames.wireNamesFor(List.of("x.a_b", "x.a.b")));

        assertTrue(thrown.getMessage().contains("collision"), thrown.getMessage());
    }

    @Test
    @DisplayName("the same id twice is not a collision")
    void duplicateIdIsFine() {
        Map<String, String> mapping =
                ToolNames.wireNamesFor(List.of("builtin.echo", "builtin.echo"));

        assertEquals(1, mapping.size());
    }

    @Test
    @DisplayName("encoding is stable, so cached prompts are not invalidated between turns")
    void deterministic() {
        assertEquals(ToolNames.toWire("mcp.server.tool"), ToolNames.toWire("mcp.server.tool"));
    }
}
