package io.jclaw.kernel.capability;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityHandler;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * A handler context carrying the credentials released into one call.
 *
 * <p>Built by the kernel after the staging request in the arguments has been checked against each
 * secret's binding, and discarded when the call returns. Everything else is delegated untouched,
 * so a lane gains one map and no new authority.
 */
final class StagedContext implements CapabilityHandler.HandlerContext {

    private final CapabilityHandler.HandlerContext base;
    private final Map<String, String> staged;

    StagedContext(CapabilityHandler.HandlerContext base, Map<String, String> staged) {
        this.base = Objects.requireNonNull(base, "base");
        this.staged = Map.copyOf(Objects.requireNonNull(staged, "staged"));
    }

    @Override
    public Result<Path, String> resolvePath(String candidate) {
        return base.resolvePath(candidate);
    }

    @Override
    public Result<URI, String> checkEgress(String url) {
        return base.checkEgress(url);
    }

    @Override
    public String displayPath(Path path) {
        return base.displayPath(path);
    }

    @Override
    public int maxOutputBytes() {
        return base.maxOutputBytes();
    }

    @Override
    public Map<String, String> stagedEnvironment() {
        return staged;
    }
}
