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
 * What the lane does when a module misbehaves.
 *
 * <p>This is the one place jclaw executes code it did not compile, so the interesting cases are
 * the ones a hostile or broken module can cause: a pointer that lands outside the memory the spec
 * allows, a result that claims to live past the end of it, a trap, and an import that was never
 * granted. Each must come back as a value the capability host can report — never an exception,
 * and never a read outside the region.
 *
 * <p>The modules are hand-assembled bytes, like {@link WasmLaneTest}\'s, so the test needs no
 * toolchain. In WebAssembly text each is the echo module with one thing changed, noted above it.
 */
class WasmLaneBoundsTest {

    private static final String ALLOC_RETURNS_ZERO =
            "AGFzbQEAAAABEQNgAX8Bf2ACf38BfmACf38AAwMCAAEFAwEAAQclAwZtZW1vcnkCAAtqY2xhd19h"
            + "bGxvYwAACmpjbGF3X2NhbGwAAQoRAgQAQQALCgBChICAgICAAgsLCwEAQYAQCwQib2si";

    private static final String RESULT_OUT_OF_BOUNDS =
            "AGFzbQEAAAABEQNgAX8Bf2ACf38BfmACf38AAwMCAAEFAwEAAQclAwZtZW1vcnkCAAtqY2xhd19h"
            + "bGxvYwAACmpjbGF3X2NhbGwAAQoUAgUAQYAICwwAQoSAgICAgICABAsLCwEAQYAQCwQib2si";

    private static final String TRAPPING =
            "AGFzbQEAAAABEQNgAX8Bf2ACf38BfmACf38AAwMCAAEFAwEAAQclAwZtZW1vcnkCAAtqY2xhd19h"
            + "bGxvYwAACmpjbGF3X2NhbGwAAQoLAgUAQYAICwMAAAs=";

    private static final String CALLS_LOG =
            "AGFzbQEAAAABEQNgAX8Bf2ACf38BfmACf38AAg0BBWpjbGF3A2xvZwACAwMCAAEFAwEAAQclAwZt"
            + "ZW1vcnkCAAtqY2xhd19hbGxvYwABCmpjbGF3X2NhbGwAAgoZAgUAQYAICxEAQYAQQQUQAEKFgICA"
            + "gIACCwsMAQBBgBALBWhlbGxv";

    private static final String WANTS_UNGRANTED =
            "AGFzbQEAAAABEQNgAX8Bf2ACf38BfmACf38AAhIBBWpjbGF3CGh0dHBfZ2V0AAIDAwIAAQUDAQAB"
            + "ByUDBm1lbW9yeQIAC2pjbGF3X2FsbG9jAAEKamNsYXdfY2FsbAACChkCBQBBgAgLEQBBgBBBBRAA"
            + "QoWAgICAgAILCwwBAEGAEAsFaGVsbG8=";

    /** Host services that answer nothing, so a refusal is what the module sees. */
    private static final class Refusing implements WasmLane.HostServices {
        final StringBuilder logged = new StringBuilder();

        @Override public void log(String message) { logged.append(message); }
        @Override public Optional<String> readFile(String path) { return Optional.empty(); }
        @Override public Optional<String> httpGet(String url) { return Optional.empty(); }
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private static WasmSpec granting(String... permissions) {
        return WasmSpec.defaults().withPermissions(Set.of(permissions));
    }

    @Test
    @DisplayName("an allocation the host cannot write into is refused, not written into anyway")
    void allocationOutsideMemoryIsRefused() {
        // (func (export "jclaw_alloc") ... i32.const 0) — a null pointer, where the arguments
        // would otherwise be copied.
        var lane = WasmLane.load(decode(ALLOC_RETURNS_ZERO), granting()).orElseThrow();

        assertEquals("module_allocation_invalid",
                lane.call("{}", new Refusing()).errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("a result pointing past the module\'s memory is refused rather than read")
    void resultOutsideMemoryIsRefused() {
        // jclaw_call answers with a pointer of 64 MiB, well past what the spec allows.
        var lane = WasmLane.load(decode(RESULT_OUT_OF_BOUNDS), granting()).orElseThrow();

        assertEquals("module_result_out_of_bounds",
                lane.call("{}", new Refusing()).errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("a trap is an outcome, not an exception the host has to catch elsewhere")
    void trapBecomesAValue() {
        // jclaw_call is a single `unreachable`.
        var lane = WasmLane.load(decode(TRAPPING), granting()).orElseThrow();

        assertEquals("module_trapped",
                lane.call("{}", new Refusing()).errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("an ungranted import is a name that does not resolve, so the module never runs")
    void ungrantedImportFailsToInstantiate() {
        // The module imports jclaw.http_get; the spec grants only log.
        var lane = WasmLane.load(decode(WANTS_UNGRANTED), granting("log")).orElseThrow();

        assertEquals("module_not_instantiable",
                lane.call("{}", new Refusing()).errorAsOptional().orElseThrow(),
                "an ungranted capability is not a guard that could be forgotten, it is a missing name");
    }

    @Test
    @DisplayName("a granted host function is callable, and what it logged precedes the result")
    void grantedImportIsCallable() {
        var lane = WasmLane.load(decode(CALLS_LOG), granting("log")).orElseThrow();
        Refusing services = new Refusing();

        String answer = lane.call("{}", services).orElseThrow();

        // The lane collects `log` itself rather than passing it to HostServices.log, which is why
        // the evidence that the import was reached is the answer and not the services object.
        assertEquals("hello\nhello", answer,
                "the module's output comes first, then its result");
        assertEquals("", services.logged.toString(),
                "HostServices.log is not on this path: the lane keeps the output and returns it");
    }

    @Test
    @DisplayName("the same module without the grant cannot run at all")
    void sameModuleWithoutTheGrant() {
        var lane = WasmLane.load(decode(CALLS_LOG), granting()).orElseThrow();

        assertEquals("module_not_instantiable",
                lane.call("{}", new Refusing()).errorAsOptional().orElseThrow(),
                "granting nothing is what makes the import unresolvable, not a check inside the call");
    }
}
