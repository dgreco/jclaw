// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools.wasm;

import io.jclaw.domain.wasm.WasmSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WASM lane against a real module.
 *
 * <p>The module is hand-assembled bytes rather than something a toolchain produced, so the test
 * needs no compiler and cannot drift from what it claims to test. In WebAssembly text it is:
 *
 * <pre>
 *   (module
 *     (memory (export "memory") 1)
 *     (data (i32.const 2048) "{\"echoed\":true}")
 *     (func (export "jclaw_alloc") (param i32) (result i32) i32.const 1024)
 *     (func (export "jclaw_call") (param i32 i32) (result i64) i64.const 8796093022223))
 * </pre>
 *
 * <p>The returned constant packs the pointer 2048 in its high half and the length 15 in its low
 * half, which is the lane's calling convention.
 */
class WasmLaneTest {

    private static final String ECHO_MODULE =
            "AGFzbQEAAAABDAJgAX8Bf2ACf38BfgMDAgABBQMBAAEHJQMGbWVtb3J5AgALamNsYXdfYWxsb2MAAApq"
            + "Y2xhd19jYWxsAAEKEgIFAEGACAsKAEKPgICAgIACCwsWAQBBgBALD3siZWNob2VkIjp0cnVlfQ==";

    private static byte[] module() {
        return Base64.getDecoder().decode(ECHO_MODULE);
    }

    /** Host services that record what was asked of them. */
    private static final class Recording implements WasmLane.HostServices {
        final StringBuilder logged = new StringBuilder();

        @Override
        public void log(String message) {
            logged.append(message);
        }

        @Override
        public Optional<String> readFile(String path) {
            return path.equals("allowed.txt") ? Optional.of("file body") : Optional.empty();
        }

        @Override
        public Optional<String> httpGet(String url) {
            return Optional.empty();
        }
    }

    @Test
    @DisplayName("a module runs and its result comes back through the calling convention")
    void runsAModule() {
        WasmLane lane = WasmLane.load(module(), WasmSpec.defaults()).orElseThrow();
        assertEquals("{\"echoed\":true}", lane.call("{\"question\":\"hi\"}", new Recording()).orElseThrow());
        // Instantiated per call, so a second call is as clean as the first.
        assertEquals("{\"echoed\":true}", lane.call("{}", new Recording()).orElseThrow());
    }

    @Test
    @DisplayName("bytes that are not a module are refused before anything is granted")
    void refusesRubbish() {
        assertEquals("module_unparseable",
                WasmLane.load("not a wasm module".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        WasmSpec.defaults()).errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("an instruction budget stops a module rather than the module stopping the host")
    void meters() {
        WasmSpec starved = new WasmSpec(4, 1, 1024, java.time.Duration.ofSeconds(1), Set.of());
        WasmLane lane = WasmLane.load(module(), starved).orElseThrow();
        var result = lane.call("{}", new Recording());
        assertTrue(result.isErr());
        assertEquals("wasm_out_of_fuel", result.errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("a result larger than the cap is refused rather than returned")
    void boundsTheResult() {
        // The module always returns fifteen bytes, so a cap below that must refuse it.
        WasmSpec tiny = new WasmSpec(4, 1_000_000L, 8, java.time.Duration.ofSeconds(1), Set.of());
        assertEquals("module_result_too_large",
                WasmLane.load(module(), tiny).orElseThrow()
                        .call("{}", new Recording()).errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("permissions are validated, and the spec's defaults grant nothing")
    void permissions() {
        assertTrue(WasmSpec.defaults().permissions().isEmpty(), "third-party code starts with nothing");
        assertTrue(WasmSpec.defaults().withPermissions(Set.of("log")).grants("log"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> WasmSpec.defaults().withPermissions(Set.of("read_everything")));
        assertEquals(Set.of("log", "read_file"),
                WasmSpec.parsePermissions(java.util.List.of(" LOG ", "read_file", "")));
        assertEquals(256 * 64 * 1024, WasmSpec.defaults().maxMemoryBytes());
    }
}
