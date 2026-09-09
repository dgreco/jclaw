package io.jclaw.app.http;

import io.jclaw.contracts.identity.Role;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;

import java.util.Objects;

/**
 * Who is calling the HTTP surface: which tenant their work belongs to, and what they may do.
 *
 * <p>A user named in {@code jclaw.serve-users}, or signed in through a session, becomes the
 * <em>tenant</em> of everything they do: their runs carry their name in {@link TurnScope#tenant()},
 * so memories, approvals, the thread lock, and the scheduler's per-tenant cap all separate by it
 * without any of those stores knowing about HTTP. Their thread names are namespaced too, since the
 * transcript is keyed by thread id alone: user {@code alice} asking for thread {@code work} reads
 * and writes {@code alice:work}.
 *
 * <p>The operator, holder of {@code jclaw.serve-token} or the sole caller when no auth is
 * configured, is the {@code local} tenant: the same one the CLI uses, so what the operator does in
 * a browser and in a terminal is one conversation. The operator may also read any tenant's runs
 * and gates; a user sees only their own.
 *
 * <p>The role governs the product surface and nothing below it. A viewer cannot start a turn; a
 * member can. Neither changes what the kernel will authorize, which is asked and answered the
 * same way whoever is calling.
 */
record Principal(String tenant, Role role) {

    /** The tenant the CLI runs as, and the operator over HTTP. */
    static final String LOCAL = TurnScope.local("p", new ThreadId("t")).tenant();

    Principal {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(role, "role");
        if (tenant.isBlank() || tenant.indexOf('/') >= 0 || tenant.indexOf(':') >= 0) {
            throw new IllegalArgumentException("tenant must be non-blank and contain no '/' or ':'");
        }
    }

    static Principal operator() {
        return new Principal(LOCAL, Role.OPERATOR);
    }

    boolean isOperator() {
        return LOCAL.equals(tenant);
    }

    /** The internal thread id for a name this principal used. Operators keep bare names. */
    ThreadId thread(String name) {
        String trimmed = name.trim();
        return new ThreadId(isOperator() ? trimmed : tenant + ":" + trimmed);
    }

    /** Whether this principal may read a run or gate in {@code scope}. */
    boolean mayRead(TurnScope scope) {
        return isOperator() || scope.tenant().equals(tenant);
    }

    /** Whether this principal may start turns and answer gates. */
    boolean mayWrite() {
        return role.canWrite();
    }
}
