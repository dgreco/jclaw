// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The families a process knows, by id.
 *
 * <p>{@link LoopFamilies} holds the two that ship. This is what lets an operator add one without
 * writing Java: the application builds a registry from configuration at startup, and the
 * interpreter resolves a run's family through it rather than through a static lookup.
 *
 * <p>Immutable, and built once. A registry that could change while runs are executing would mean
 * a resumed turn finding a different machine than the one that checkpointed it — the same reason
 * the model and system prompt are resolved at admission and stored on the run.
 *
 * <p>The built-in ids cannot be shadowed. An operator who defines a family called
 * {@code canonical} is almost certainly making a mistake, and silently replacing the machine
 * every other run uses is the worst way to find out.
 */
public final class LoopFamilyRegistry {

    private static final LoopFamilyRegistry BUILT_IN = new LoopFamilyRegistry(Map.of());

    private final Map<String, LoopFamily> configured;

    private LoopFamilyRegistry(Map<String, LoopFamily> configured) {
        this.configured = Map.copyOf(configured);
    }

    /** Only the families that ship. */
    public static LoopFamilyRegistry builtIn() {
        return BUILT_IN;
    }

    /**
     * The built-in families plus the configured ones.
     *
     * @throws IllegalArgumentException when a configured id shadows a built-in one
     */
    public static LoopFamilyRegistry of(List<LoopFamily> families) {
        Objects.requireNonNull(families, "families");
        Map<String, LoopFamily> byId = new LinkedHashMap<>();
        for (LoopFamily family : families) {
            String id = normalise(family.id());
            if (LoopFamilies.byId(id).isPresent()) {
                throw new IllegalArgumentException(
                        "loop family '" + id + "' is built in and cannot be redefined");
            }
            if (byId.put(id, family) != null) {
                throw new IllegalArgumentException("loop family '" + id + "' is defined twice");
            }
        }
        return byId.isEmpty() ? BUILT_IN : new LoopFamilyRegistry(byId);
    }

    /** The family for an id, or empty when nothing defines it. */
    public Optional<LoopFamily> byId(String id) {
        String key = normalise(id);
        Optional<LoopFamily> builtIn = LoopFamilies.byId(key);
        return builtIn.isPresent() ? builtIn : Optional.ofNullable(configured.get(key));
    }

    /** Every id this registry answers to, built-ins first. */
    public List<String> ids() {
        List<String> ids = new java.util.ArrayList<>(List.of("canonical", "reflective"));
        ids.addAll(configured.keySet());
        return List.copyOf(ids);
    }

    /** Whether an id names a family here. */
    public boolean knows(String id) {
        return byId(id).isPresent();
    }

    private static String normalise(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
