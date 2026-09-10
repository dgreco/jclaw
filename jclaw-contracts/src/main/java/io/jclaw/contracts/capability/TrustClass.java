// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.capability;

/**
 * Provenance of a capability, assigned by the host.
 *
 * <p>Critically: trust is <em>never</em> self-declared. A manifest cannot mark itself first-party,
 * a loop cannot promote a capability it wants to call, and a model certainly cannot. The host
 * assigns the class from where the capability actually came, which is the whole reason a hostile
 * extension cannot escalate by lying about itself.
 */
public enum TrustClass {

    /** Host-owned, compiled in. The only class permitted {@link EffectClass#DESTRUCTIVE} by default. */
    SYSTEM,

    /** Shipped built-ins registered by the host at startup. */
    FIRST_PARTY,

    /** Third-party, signature verified against a known publisher. */
    VERIFIED,

    /** Third-party, installed by the user, unverified. */
    COMMUNITY,

    /** Unknown provenance. Maximum suspicion; approval required for anything but pure effects. */
    UNTRUSTED;

    /**
     * The strongest effect this trust class may exercise without an explicit human approval.
     * Anything above the returned class raises an approval gate.
     *
     * <p>Note the deliberate split. For <b>third-party</b> code the ceiling is the binding
     * constraint and policy can only tighten it further: an untrusted extension does not become
     * safe because an operator raised a setting. For <b>host-shipped</b> capabilities
     * ({@link #SYSTEM}, {@link #FIRST_PARTY}) the operator's {@code CapabilityPolicy} is
     * authoritative, because code the host ships is exactly as trustworthy as the host itself and
     * the meaningful question is what the operator wants this deployment to permit.
     *
     * <p>Getting this wrong in the other direction is worse than it looks: capping first-party at
     * {@code WRITE_LOCAL} made {@code shell} gate even under an explicitly trusted policy, which
     * made "trusted" mode indistinguishable from "interactive" for the one capability anybody
     * enables it for — and taught operators that the setting does not work.
     */
    public EffectClass autoApprovalCeiling() {
        return switch (this) {
            // Host-shipped: policy decides. DESTRUCTIVE still always gates (see EffectClass).
            case SYSTEM, FIRST_PARTY -> EffectClass.PROCESS;
            // Third-party: trust binds, and policy may only tighten from here.
            case VERIFIED -> EffectClass.READ_LOCAL;
            case COMMUNITY, UNTRUSTED -> EffectClass.PURE;
        };
    }

    /** Whether {@code effect} may proceed unattended under this trust class. */
    public boolean permitsUnattended(EffectClass effect) {
        return effect.compareTo(autoApprovalCeiling()) <= 0;
    }
}
