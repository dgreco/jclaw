// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity of a capability, namespaced as {@code <namespace>.<name>}.
 *
 * <p>e.g. {@code builtin.read_file}, {@code mcp.github.create_issue}. The namespace is not
 * decoration — authorization rules are written against it, so a capability cannot masquerade as
 * a built-in by choosing a clever name.
 */
public record CapabilityId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

    public CapabilityId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "capability id must be lower_snake segments separated by '.', got: " + value);
        }
    }

    public static CapabilityId of(String value) {
        return new CapabilityId(value);
    }

    /** Convenience for the host's compiled-in capabilities. */
    public static CapabilityId builtin(String name) {
        return new CapabilityId("builtin." + name);
    }

    /** The leading segment, e.g. {@code builtin} or {@code mcp}. */
    public String namespace() {
        return value.substring(0, value.indexOf('.'));
    }

    /** Everything after the first segment. */
    public String name() {
        return value.substring(value.indexOf('.') + 1);
    }

    public boolean isBuiltin() {
        return "builtin".equals(namespace());
    }

    @Override
    public String toString() {
        return value;
    }
}
