package io.jclaw.kernel.capability;

import io.jclaw.contracts.turn.TurnScope;

import java.util.Map;
import java.util.Objects;

/**
 * Which policy applies to a turn.
 *
 * <p>One harness can serve several tenants, and they need not be trusted equally: the operator's
 * own runs may execute shell commands unattended while a guest's must ask about everything. The
 * posture is therefore resolved per scope rather than fixed for the process.
 *
 * <p>Resolution can only tighten. A tenant policy is chosen from the same three postures the
 * operator can choose from and inherits the host's denials on top of its own, so no tenant
 * configuration can widen what the process as a whole permits. That is the same rule the
 * per-tool egress lists follow, for the same reason.
 */
@FunctionalInterface
public interface CapabilityPolicyResolver {

    CapabilityPolicy forScope(TurnScope scope);

    /** The single-posture case: every tenant gets the same policy. */
    static CapabilityPolicyResolver fixed(CapabilityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return scope -> policy;
    }

    /**
     * A policy per tenant, falling back to {@code base}.
     *
     * @param byTenant tenant name to the policy for it, already narrowed against {@code base}
     */
    static CapabilityPolicyResolver byTenant(CapabilityPolicy base, Map<String, CapabilityPolicy> byTenant) {
        Objects.requireNonNull(base, "base");
        Map<String, CapabilityPolicy> copy = Map.copyOf(Objects.requireNonNull(byTenant, "byTenant"));
        return scope -> copy.getOrDefault(scope.tenant(), base);
    }
}
