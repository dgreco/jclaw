// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.turn;

/**
 * Conversation thread identity. Part of {@link TurnScope}, and therefore part of the active-lock key.
 */
public record ThreadId(String value) implements Ident {

    public ThreadId {
        value = Ident.validate(value, "ThreadId");
    }

    /** Mints a fresh, unique ThreadId. */
    public static ThreadId fresh() {
        return new ThreadId(Ident.fresh("thr"));
    }

    @Override
    public String toString() {
        return value;
    }
}
