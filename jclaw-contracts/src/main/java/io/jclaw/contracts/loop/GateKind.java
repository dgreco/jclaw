// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.loop;

import io.jclaw.contracts.turn.TurnStatus;

/** Why a run is parked. Each gate maps to exactly one blocked {@link TurnStatus}. */
public enum GateKind {

    /** A human must approve a specific capability invocation before it may dispatch. */
    APPROVAL(TurnStatus.BLOCKED_APPROVAL),

    /** Credentials are missing or expired; the user must authenticate. */
    AUTH(TurnStatus.BLOCKED_AUTH),

    /** An external process or child run must finish first. */
    PROCESS(TurnStatus.WAITING_PROCESS);

    private final TurnStatus blockedStatus;

    GateKind(TurnStatus blockedStatus) {
        this.blockedStatus = blockedStatus;
    }

    public TurnStatus blockedStatus() {
        return blockedStatus;
    }
}
