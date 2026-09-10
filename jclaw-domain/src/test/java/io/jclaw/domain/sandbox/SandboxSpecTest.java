// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxSpecTest {

    @Test
    @DisplayName("the default spec isolates network, memory, cpu, pids, and the root filesystem")
    void defaultsAreConservative() {
        SandboxSpec spec = SandboxSpec.defaults("docker", "alpine:3.20");
        List<String> argv = spec.argv(Path.of("/tmp/ws"), "ls -la");

        assertEquals("docker", argv.get(0));
        assertTrue(argv.containsAll(List.of("run", "--rm", "--network", "none", "--memory", "512m",
                "--cpus", "1", "--pids-limit", "256", "--read-only")));
        assertTrue(argv.contains("/tmp/ws:/workspace:rw"), "the workspace is the only mount");
        assertTrue(argv.contains("--workdir") && argv.contains("/workspace"));
        assertEquals(List.of("alpine:3.20", "/bin/sh", "-c", "ls -la"), argv.subList(argv.size() - 4, argv.size()));
        assertFalse(argv.contains("--privileged"));
    }

    @Test
    @DisplayName("the environment inside is only what the spec sets")
    void environmentIsExplicit() {
        List<String> argv = SandboxSpec.defaults("docker", "img").argv(Path.of("/w"), "env");
        long envFlags = argv.stream().filter("--env"::equals).count();
        assertEquals(2, envFlags);
        assertTrue(argv.contains("HOME=/workspace"));
    }

    @Test
    @DisplayName("a program runs as-is, with environment passed by name only")
    void programForm() {
        List<String> argv = SandboxSpec.defaults("docker", "node:22-alpine")
                .argv(Path.of("/w"), List.of("npx", "-y", "server"), java.util.Set.of("API_KEY"));
        assertEquals(List.of("node:22-alpine", "npx", "-y", "server"), argv.subList(argv.size() - 4, argv.size()));
        assertTrue(argv.contains("API_KEY"), "the name is passed for docker to read from its environment");
        assertFalse(argv.stream().anyMatch(a -> a.startsWith("API_KEY=")), "never the value");
        assertFalse(argv.contains("/bin/sh"));
        assertThrows(IllegalArgumentException.class, () -> SandboxSpec.defaults("docker", "img")
                .argv(Path.of("/w"), List.of("x"), java.util.Set.of("KEY=value")));
        assertThrows(IllegalArgumentException.class, () -> SandboxSpec.defaults("docker", "img")
                .argv(Path.of("/w"), List.of(), java.util.Set.of()));
    }

    @Test
    @DisplayName("network can be opened deliberately; nonsense is refused")
    void validation() {
        SandboxSpec open = new SandboxSpec("docker", "img", "bridge", "1g", "2", 512, false);
        List<String> argv = open.argv(Path.of("/w"), "curl x");
        assertTrue(argv.contains("bridge"));
        assertFalse(argv.contains("--read-only"));
        assertThrows(IllegalArgumentException.class, () -> new SandboxSpec("", "img", "none", "1g", "1", 1, true));
        assertThrows(IllegalArgumentException.class, () -> new SandboxSpec("docker", "img", "none", "1g", "1", 0, true));
    }
}
