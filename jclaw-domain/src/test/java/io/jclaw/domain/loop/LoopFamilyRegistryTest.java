// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A family an operator defines is found alongside the built-ins and cannot replace one. */
class LoopFamilyRegistryTest {

    @Test
    @DisplayName("the built-ins are always there, configured or not")
    void builtInsAlwaysResolve() {
        LoopFamilyRegistry registry = LoopFamilyRegistry.builtIn();
        assertSame(LoopFamilies.CANONICAL, registry.byId("canonical").orElseThrow());
        assertSame(LoopFamilies.REFLECTIVE, registry.byId("reflective").orElseThrow());
        assertSame(LoopFamilies.CANONICAL, registry.byId("  CANONICAL  ").orElseThrow(),
                "ids are matched case- and space-insensitively, as configuration arrives");
        assertTrue(registry.byId("nothing-defines-this").isEmpty());
        assertFalse(registry.knows("nothing-defines-this"));
    }

    @Test
    @DisplayName("a configured family resolves by its own id")
    void configuredFamilyResolves() {
        LoopFamily reviewer = LoopFamilies.reviewing("strict", "Check that the migration is reversible.");
        LoopFamilyRegistry registry = LoopFamilyRegistry.of(List.of(reviewer));

        assertSame(reviewer, registry.byId("strict").orElseThrow());
        assertTrue(registry.knows("STRICT"));
        assertEquals(List.of("canonical", "reflective", "strict"), registry.ids());
        assertSame(LoopFamilies.CANONICAL, registry.byId("canonical").orElseThrow(),
                "adding one does not displace the built-ins");
    }

    @Test
    @DisplayName("a configured id may not shadow a built-in, or be defined twice")
    void refusesShadowingAndDuplicates() {
        assertEquals("loop family 'canonical' is built in and cannot be redefined",
                assertThrows(IllegalArgumentException.class, () -> LoopFamilyRegistry.of(
                        List.of(LoopFamilies.reviewing("canonical", "x")))).getMessage(),
                "silently replacing the machine every other run uses is the worst way to find out");
        assertEquals("loop family 'reflective' is built in and cannot be redefined",
                assertThrows(IllegalArgumentException.class, () -> LoopFamilyRegistry.of(
                        List.of(LoopFamilies.reviewing("reflective", "x")))).getMessage());
        assertEquals("loop family 'twice' is defined twice",
                assertThrows(IllegalArgumentException.class, () -> LoopFamilyRegistry.of(List.of(
                        LoopFamilies.reviewing("twice", "a"),
                        LoopFamilies.reviewing("twice", "b")))).getMessage());
    }

    @Test
    @DisplayName("a reviewing family needs an id and an instruction")
    void reviewingIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> LoopFamilies.reviewing("", "x"));
        assertThrows(IllegalArgumentException.class, () -> LoopFamilies.reviewing("id", ""));
        assertThrows(IllegalArgumentException.class, () -> LoopFamilies.reviewing("id", "   "));
        assertEquals("strict", LoopFamilies.reviewing("strict", "check it").id());
    }

    @Test
    @DisplayName("a policy resolves its family through the registry it is given")
    void policyResolvesThroughRegistry() {
        LoopFamily reviewer = LoopFamilies.reviewing("strict", "check it");
        LoopPolicy policy = LoopPolicy.of("m", "s", List.of()).withFamily("strict");

        assertSame(reviewer, policy.loopFamily(LoopFamilyRegistry.of(List.of(reviewer))));
        assertThrows(IllegalStateException.class, () -> policy.loopFamily(LoopFamilyRegistry.builtIn()),
                "a policy naming a family this process does not have is a wiring error, not a silent fallback");
    }
}
