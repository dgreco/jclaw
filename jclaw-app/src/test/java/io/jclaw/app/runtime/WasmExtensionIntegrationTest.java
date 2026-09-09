package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.TrustClass;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;
import io.jclaw.domain.wasm.WasmSpec;
import io.jclaw.storage.extension.FilesystemExtensionRegistry;
import io.jclaw.storage.jsonl.JsonlFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A WebAssembly extension from package to capability: installed, loaded, and called through the
 * same handler interface a built-in uses.
 *
 * <p>The module is the hand-assembled one from the lane's own test, which returns a fixed JSON
 * document. What is being tested here is the path around it, not the module.
 */
class WasmExtensionIntegrationTest {

    private static final String ECHO_MODULE =
            "AGFzbQEAAAABDAJgAX8Bf2ACf38BfgMDAgABBQMBAAEHJQMGbWVtb3J5AgALamNsYXdfYWxsb2MAAApq"
            + "Y2xhd19jYWxsAAEKEgIFAEGACAsKAEKPgICAgIACCwsWAQBBgBALD3siZWNob2VkIjp0cnVlfQ==";

    @TempDir Path dir;

    private FilesystemExtensionRegistry registry() {
        return new FilesystemExtensionRegistry(dir.resolve("extensions"),
                new JsonlFile(dir.resolve("extensions.jsonl")), Map.of(), Optional.empty(), Clock.systemUTC());
    }

    private Path packageDir(String name, String permissions, boolean withModule) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("pkg-" + name));
        Files.writeString(pkg.resolve("jclaw-extension.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1.0\",\"kind\":\"wasm\","
                        + "\"description\":\"echoes a fixed answer\","
                        + "\"permissions\":[" + permissions + "],\"tools\":[\"echo\"]}");
        if (withModule) {
            Files.write(pkg.resolve("module.wasm"), Base64.getDecoder().decode(ECHO_MODULE));
        }
        return pkg;
    }

    @Test
    @DisplayName("an installed module becomes a namespaced capability that runs")
    void installedModuleRuns() throws IOException {
        FilesystemExtensionRegistry extensions = registry();
        ExtensionRegistry.Installed installed =
                extensions.install(packageDir("echoer", "\"log\"", true), Map.of()).orElseThrow();
        assertEquals(ExtensionRegistry.Kind.WASM, installed.manifest().kind());
        assertEquals(TrustClass.COMMUNITY, installed.trust());
        assertEquals(EffectClass.NETWORK, installed.effectiveEffect(),
                "an unsigned module's claim about its own effect is not believed");

        WasmExtensionHost host = new WasmExtensionHost(
                extensions, dir.resolve("extensions"), WasmSpec.defaults());
        List<CapabilityHandler> handlers = host.handlers();
        assertEquals(List.of("wasm.echoer.echo"),
                handlers.stream().map(h -> h.descriptor().id().value()).toList());
        assertTrue(handlers.get(0).descriptor().description().contains("via WASM extension 'echoer'"));

        String answer = handlers.get(0).execute(new CapabilityInvocation(
                CapabilityId.of("wasm.echoer.echo"), "c1", Map.of("q", "hi"),
                TurnScope.local("p", new ThreadId("t")), new TurnRunId("run_1")), null).orElseThrow();
        assertEquals("{\"echoed\":true}", answer);
    }

    @Test
    @DisplayName("a package without a module, or wanting a permission that does not exist, is refused")
    void refusals() throws IOException {
        FilesystemExtensionRegistry extensions = registry();
        assertEquals("wasm_package_needs_module.wasm",
                extensions.install(packageDir("nomodule", "", false), Map.of())
                        .errorAsOptional().orElseThrow());
        assertEquals("wasm_permission_unknown",
                extensions.install(packageDir("greedy", "\"read_everything\"", true), Map.of())
                        .errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("a disabled extension contributes nothing, and a broken module is skipped, not fatal")
    void disabledAndBroken() throws IOException {
        FilesystemExtensionRegistry extensions = registry();
        extensions.install(packageDir("echoer", "", true), Map.of()).orElseThrow();
        assertTrue(extensions.setEnabled("echoer", false));
        assertTrue(new WasmExtensionHost(extensions, dir.resolve("extensions"), WasmSpec.defaults())
                .handlers().isEmpty());

        assertTrue(extensions.setEnabled("echoer", true));
        // Corrupt the installed module: loading fails, and the host carries on without it.
        Files.write(dir.resolve("extensions/echoer/module.wasm"), "not wasm".getBytes());
        assertTrue(new WasmExtensionHost(extensions, dir.resolve("extensions"), WasmSpec.defaults())
                .handlers().isEmpty(), "a broken package costs its own tools, not the process");
    }
}
