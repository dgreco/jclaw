// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.kernel.capability;

import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.EffectClass;

import io.jclaw.domain.policy.RateLimit;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The host's decision rules for whether a capability may run unattended.
 *
 * <p>Policy composes with, and may only tighten, what a capability's own trust and effect classes
 * already permit. It cannot grant more: a {@link io.jclaw.contracts.capability.TrustClass#UNTRUSTED}
 * extension does not become safe because an operator raised a ceiling.
 *
 * @param autoApproveCeiling strongest effect that runs without asking; anything above needs a human
 * @param denied             hard denials, checked before everything else
 * @param interactive        whether a human is actually reachable. When false, work that would
 *                           need approval is <em>denied</em> rather than parked — a gate nobody can
 *                           answer is just a hung run, and failing closed is the honest outcome.
 * @param injection          what to do with tool output that looks like a prompt injection
 * @param toolEgress         per-capability egress allowlists, applied on top of the host guard;
 *                           a tool absent here keeps the host-wide posture
 * @param rateLimits         per-capability invocation limits, enforced per process
 */
public record CapabilityPolicy(
        EffectClass autoApproveCeiling,
        Set<CapabilityId> denied,
        boolean interactive,
        InjectionPolicy injection,
        Map<CapabilityId, Set<String>> toolEgress,
        Map<CapabilityId, RateLimit> rateLimits) {

    public CapabilityPolicy {
        Objects.requireNonNull(autoApproveCeiling, "autoApproveCeiling");
        denied = Set.copyOf(Objects.requireNonNull(denied, "denied"));
        Objects.requireNonNull(injection, "injection");
        toolEgress = Map.copyOf(Objects.requireNonNull(toolEgress, "toolEgress"));
        rateLimits = Map.copyOf(Objects.requireNonNull(rateLimits, "rateLimits"));
    }

    /** With the default injection policy and no per-tool limits. */
    public CapabilityPolicy(EffectClass autoApproveCeiling, Set<CapabilityId> denied, boolean interactive) {
        this(autoApproveCeiling, denied, interactive, InjectionPolicy.SANITIZE, Map.of(), Map.of());
    }

    /** With no per-tool limits. */
    public CapabilityPolicy(
            EffectClass autoApproveCeiling, Set<CapabilityId> denied, boolean interactive,
            InjectionPolicy injection) {
        this(autoApproveCeiling, denied, interactive, injection, Map.of(), Map.of());
    }

    /**
     * The default interactive posture: reads run freely, anything that writes, reaches the
     * network, or spawns a process asks first.
     */
    public static CapabilityPolicy interactiveDefault() {
        return new CapabilityPolicy(EffectClass.READ_LOCAL, Set.of(), true);
    }

    /** Non-interactive posture for scripted runs: read-only, and no gates can be answered. */
    public static CapabilityPolicy unattended() {
        return new CapabilityPolicy(EffectClass.READ_LOCAL, Set.of(), false);
    }

    /**
     * Permits everything up to and including process execution without asking.
     *
     * <p>For trusted local development only. This is the posture where a prompt injection becomes
     * arbitrary code execution, so it should never be a deployment default.
     */
    public static CapabilityPolicy trustedLocal() {
        return new CapabilityPolicy(EffectClass.PROCESS, Set.of(), true);
    }

    public CapabilityPolicy withDenied(Set<CapabilityId> denied) {
        return new CapabilityPolicy(autoApproveCeiling, denied, interactive, injection, toolEgress, rateLimits);
    }

    public CapabilityPolicy withInteractive(boolean interactive) {
        return new CapabilityPolicy(autoApproveCeiling, denied, interactive, injection, toolEgress, rateLimits);
    }

    public CapabilityPolicy withInjection(InjectionPolicy injection) {
        return new CapabilityPolicy(autoApproveCeiling, denied, interactive, injection, toolEgress, rateLimits);
    }

    public CapabilityPolicy withToolEgress(Map<CapabilityId, Set<String>> toolEgress) {
        return new CapabilityPolicy(autoApproveCeiling, denied, interactive, injection, toolEgress, rateLimits);
    }

    public CapabilityPolicy withRateLimits(Map<CapabilityId, RateLimit> rateLimits) {
        return new CapabilityPolicy(autoApproveCeiling, denied, interactive, injection, toolEgress, rateLimits);
    }

    public boolean isDenied(CapabilityId id) {
        return denied.contains(id);
    }

    /**
     * Whether this capability may run without a human, taking the stricter of the policy ceiling
     * and the capability's own trust ceiling.
     */
    public boolean permitsUnattended(CapabilityDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        boolean withinPolicy = descriptor.effect().compareTo(autoApproveCeiling) <= 0;
        return withinPolicy && descriptor.permitsUnattended();
    }
}
