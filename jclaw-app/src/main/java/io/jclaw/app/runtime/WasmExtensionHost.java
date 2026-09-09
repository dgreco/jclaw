package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.domain.wasm.WasmSpec;
import io.jclaw.tools.wasm.WasmCapabilityHandler;
import io.jclaw.tools.wasm.WasmLane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns installed WebAssembly extensions into capabilities.
 *
 * <p>A module is loaded once, at startup, and instantiated per call by the lane. Loading is where
 * a malformed module is caught, so a broken package costs a warning rather than a failure in the
 * middle of somebody's turn.
 *
 * <p>The permissions are the manifest's, and for an unsigned package that is a claim by whoever
 * wrote it. What makes the claim safe to honour is that permissions only ever open host functions
 * the host itself implements, each of which is already behind the workspace or egress guard, and
 * that every call still goes through the authority gate at {@code COMMUNITY} trust. A verified
 * package's manifest is believed further, in that its declared effect class is used.
 */
public class WasmExtensionHost {

    private static final Logger log = LoggerFactory.getLogger(WasmExtensionHost.class);

    private final List<CapabilityHandler> handlers = new ArrayList<>();

    public WasmExtensionHost(ExtensionRegistry extensions, Path extensionsRoot, WasmSpec baseSpec) {
        Objects.requireNonNull(extensionsRoot, "extensionsRoot");
        Objects.requireNonNull(baseSpec, "baseSpec");
        if (extensions == null) {
            return;
        }
        for (ExtensionRegistry.Installed installed : extensions.list()) {
            if (!installed.enabled() || installed.manifest().kind() != ExtensionRegistry.Kind.WASM) {
                continue;
            }
            Path module = extensionsRoot.resolve(installed.name())
                    .resolve(io.jclaw.storage.extension.FilesystemExtensionRegistry.MODULE_FILE);
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(module);
            } catch (IOException e) {
                log.debug("wasm: {} has no readable module at {}", installed.name(), module);
                continue;
            }
            WasmSpec spec;
            try {
                spec = baseSpec.withPermissions(WasmSpec.parsePermissions(installed.manifest().permissions()));
            } catch (IllegalArgumentException e) {
                log.debug("wasm: {} asks for a permission that does not exist", installed.name());
                continue;
            }
            WasmLane.load(bytes, spec).fold(
                    lane -> {
                        for (String tool : installed.manifest().tools()) {
                            handlers.add(new WasmCapabilityHandler(installed.name(), tool,
                                    installed.manifest().description(), lane,
                                    installed.trust(), installed.effectiveEffect()));
                        }
                        log.debug("wasm: {} offers {} tool(s) with permissions {}",
                                installed.name(), installed.manifest().tools().size(), spec.permissions());
                        return true;
                    },
                    reason -> {
                        log.debug("wasm: {} could not be loaded ({})", installed.name(), reason);
                        return false;
                    });
        }
    }

    /** The capabilities every enabled WASM extension contributes. */
    public List<CapabilityHandler> handlers() {
        return List.copyOf(handlers);
    }
}
