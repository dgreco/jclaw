// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.turn;

/**
 * Identity of an approval or auth gate raised during a run.
 */
public record GateId(String value) implements Ident {

    public GateId {
        value = Ident.validate(value, "GateId");
    }

    /** Mints a fresh, unique GateId. */
    public static GateId fresh() {
        return new GateId(Ident.fresh("gate"));
    }

    @Override
    public String toString() {
        return value;
    }
}
