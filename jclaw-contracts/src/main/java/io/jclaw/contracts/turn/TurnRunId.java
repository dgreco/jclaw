// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.turn;

/**
 * Executable run identity. A turn may span several runs across block and resume, but only one may be Running at a time.
 */
public record TurnRunId(String value) implements Ident {

    public TurnRunId {
        value = Ident.validate(value, "TurnRunId");
    }

    /** Mints a fresh, unique TurnRunId. */
    public static TurnRunId fresh() {
        return new TurnRunId(Ident.fresh("run"));
    }

    @Override
    public String toString() {
        return value;
    }
}
