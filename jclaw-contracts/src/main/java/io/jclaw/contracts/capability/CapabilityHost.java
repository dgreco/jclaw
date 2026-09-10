// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.capability;

import io.jclaw.contracts.turn.TurnScope;

import java.util.List;
import java.util.Optional;

/**
 * The authority gate. Every capability effect in the system passes through here.
 *
 * <p>This is the narrow waist of the security model: a loop may <em>request</em> an effect, and
 * this decides whether it happens. Loops never reach a runtime lane directly — the dispatcher
 * below this interface routes already-authorized work and is not itself a permission API.
 *
 * <p>Fails closed by construction. {@link #invoke} returns a {@link CapabilityOutcome}, and the
 * denial branch is a normal value, so a caller that ignores the distinction gets a denial it must
 * still handle rather than an effect it did not earn.
 */
public interface CapabilityHost {

    /**
     * Capabilities publishable to the model for this scope.
     *
     * <p>Visibility is metadata, never a grant. Showing a capability here does not authorize it,
     * and hiding one does not secure it — a direct invocation of a hidden capability must still
     * fail closed in {@link #invoke}.
     */
    List<CapabilityDescriptor> visibleSurface(TurnScope scope);

    /** Looks up a descriptor regardless of visibility. */
    Optional<CapabilityDescriptor> describe(CapabilityId id);

    /**
     * Authorizes and, if permitted, dispatches one exact invocation.
     *
     * <p>The full decision path runs here: existence, visibility, trust and effect policy,
     * approval gates, resource reservation, egress and workspace guards, dispatch, then output
     * redaction. An unknown capability yields {@link CapabilityOutcome.Denied}, never an
     * exception, so an adversarial tool name is ordinary control flow.
     */
    CapabilityOutcome invoke(CapabilityInvocation invocation);
}
