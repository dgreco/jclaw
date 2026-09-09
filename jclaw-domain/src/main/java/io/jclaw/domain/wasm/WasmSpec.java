package io.jclaw.domain.wasm;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a WebAssembly module is allowed: how much memory, how much computation, how long, and
 * which host functions it may import.
 *
 * <p>The container sandbox bounds a process by asking the operating system. A WASM module has no
 * process to bound, so the limits are the runtime's own: pages of linear memory it may address,
 * instructions it may execute before it is stopped, and the wall clock as a backstop. Together
 * they mean a module cannot exhaust the host by looping, allocating, or blocking.
 *
 * <p>The permissions are the interesting part, and they are enforced by absence rather than by a
 * check. A module declares the host functions it imports; the host supplies only those the
 * operator granted; a module importing anything else fails to instantiate and never runs at all.
 * There is no call to forget to guard, because an ungranted capability is not a function that
 * refuses, it is a function that does not exist.
 */
public record WasmSpec(
        int maxMemoryPages,
        long maxInstructions,
        int maxOutputBytes,
        Duration timeout,
        Set<String> permissions) {

    /** A page of WebAssembly linear memory. */
    public static final int PAGE_BYTES = 64 * 1024;

    /** Host functions a module may be granted. Anything else is not a name the host knows. */
    public static final Set<String> KNOWN_PERMISSIONS = Set.of("log", "read_file", "http_get");

    public WasmSpec {
        Objects.requireNonNull(timeout, "timeout");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (maxMemoryPages <= 0) {
            throw new IllegalArgumentException("maxMemoryPages must be positive");
        }
        if (maxInstructions <= 0) {
            throw new IllegalArgumentException("maxInstructions must be positive");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        for (String permission : permissions) {
            if (!KNOWN_PERMISSIONS.contains(permission)) {
                throw new IllegalArgumentException("unknown wasm permission '" + permission
                        + "'; known: " + KNOWN_PERMISSIONS);
            }
        }
    }

    /**
     * A conservative default: sixteen megabytes, a hundred million instructions, five seconds,
     * and nothing granted. A module that wants to touch the world has to be given permission
     * explicitly, which is the only default worth having for third-party code.
     */
    public static WasmSpec defaults() {
        return new WasmSpec(256, 100_000_000L, 256 * 1024, Duration.ofSeconds(5), Set.of());
    }

    public WasmSpec withPermissions(Set<String> permissions) {
        return new WasmSpec(maxMemoryPages, maxInstructions, maxOutputBytes, timeout, permissions);
    }

    public boolean grants(String permission) {
        return permissions.contains(permission);
    }

    public int maxMemoryBytes() {
        return maxMemoryPages * PAGE_BYTES;
    }

    /** Parses permissions as a manifest lists them, rejecting any the host does not implement. */
    public static Set<String> parsePermissions(Iterable<String> declared) {
        Objects.requireNonNull(declared, "declared");
        Set<String> parsed = new java.util.LinkedHashSet<>();
        for (String permission : declared) {
            String cleaned = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty()) {
                parsed.add(cleaned);
            }
        }
        return Set.copyOf(parsed);
    }
}
