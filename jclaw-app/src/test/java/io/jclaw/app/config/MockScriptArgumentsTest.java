// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The mock script is how the CLI is exercised without a network, so a capability the script
 * cannot express is a capability the CLI cannot be tested against.
 */
class MockScriptArgumentsTest {

    @Test
    @DisplayName("commas inside a JSON value do not split the argument list")
    void splitsOutsideJson() {
        assertEquals(List.of("a=1", "b=2"), JclawConfiguration.splitArguments("a=1,b=2"));
        assertEquals(List.of("command=ls", "secret_env={\"A\":\"x\",\"B\":\"y\"}"),
                JclawConfiguration.splitArguments("command=ls,secret_env={\"A\":\"x\",\"B\":\"y\"}"));
        assertEquals(List.of("tags=[\"a\",\"b\"]", "n=1"),
                JclawConfiguration.splitArguments("tags=[\"a\",\"b\"],n=1"));
        assertEquals(List.of("text=hello", " world"),
                JclawConfiguration.splitArguments("text=hello, world"),
                "a comma in a plain string still splits — the shape is k=v, and that has not changed. "
                        + "Spring splits a list property on commas first anyway, so a plain value "
                        + "never reaches here whole; only JSON, which is passed indexed, does.");
        assertEquals(List.of(), JclawConfiguration.splitArguments(""));
    }

    @Test
    @DisplayName("a value is a string unless it opens a JSON object or array")
    void parsesStructuredValues() {
        assertEquals("ls -la", JclawConfiguration.parseArgumentValue("ls -la", 1));
        assertEquals("", JclawConfiguration.parseArgumentValue("", 1));
        assertEquals(Map.of("A", "x"), JclawConfiguration.parseArgumentValue("{\"A\":\"x\"}", 1));
        assertEquals(List.of("a", "b"), JclawConfiguration.parseArgumentValue("[\"a\",\"b\"]", 1));
        assertEquals("{not json", assertThrows(IllegalArgumentException.class,
                () -> JclawConfiguration.parseArgumentValue("{not json", 3)).getMessage()
                .replaceAll(".*'(.*)'.*", "$1"));
    }
}
