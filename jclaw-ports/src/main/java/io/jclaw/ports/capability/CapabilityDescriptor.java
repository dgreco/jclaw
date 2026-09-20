// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.capability;

import io.jclaw.ports.model.ModelExchange.ToolSpec;

import java.util.Map;
import java.util.Objects;

/**
 * Everything the host knows about a capability before anyone invokes it.
 *
 * <p>Descriptors are <em>publication metadata</em>. Appearing in a descriptor — even one visible
 * to the model — grants nothing: the kernel still authorizes each exact invocation. That
 * separation is what makes it safe to show the model a broad tool surface, and it is why a
 * capability hidden from the surface must still fail closed when invoked directly.
 *
 * @param id          namespaced identity
 * @param description one line, shown to the model
 * @param inputSchema JSON Schema for arguments
 * @param effect      what it does to the world, as assigned by the host
 * @param trust       provenance, as assigned by the host
 */
public record CapabilityDescriptor(
        CapabilityId id,
        String description,
        Map<String, Object> inputSchema,
        EffectClass effect,
        TrustClass trust) {

    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(trust, "trust");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank for " + id);
        }
    }

    /** Convenience for host built-ins, which are first-party by definition. */
    public static CapabilityDescriptor builtin(
            String name, String description, EffectClass effect, Map<String, Object> schema) {
        return new CapabilityDescriptor(
                CapabilityId.builtin(name), description, schema, effect, TrustClass.FIRST_PARTY);
    }

    /** Projects to the tool shape the model sees. Deliberately drops trust and effect metadata. */
    public ToolSpec toToolSpec() {
        return new ToolSpec(id.value(), description, inputSchema);
    }

    /**
     * Whether an invocation may proceed without human approval, given this capability's own trust
     * and effect. Policy may be stricter; it may never be more permissive than this.
     */
    public boolean permitsUnattended() {
        return trust.permitsUnattended(effect);
    }
}
