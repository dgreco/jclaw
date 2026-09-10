// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.turn;

/**
 * Accepted inbound turn identity. One per admitted user message.
 */
public record TurnId(String value) implements Ident {

    public TurnId {
        value = Ident.validate(value, "TurnId");
    }

    /** Mints a fresh, unique TurnId. */
    public static TurnId fresh() {
        return new TurnId(Ident.fresh("turn"));
    }

    @Override
    public String toString() {
        return value;
    }
}
