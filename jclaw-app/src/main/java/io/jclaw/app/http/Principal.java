package io.jclaw.app.http;

import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnScope;

import java.util.Objects;

/**
 * Who is calling the HTTP surface, and therefore which tenant their runs belong to.
 *
 * <p>A user named in {@code jclaw.serve-users} becomes the <em>tenant</em> of everything they do:
 * their runs carry their name in {@link TurnScope#tenant()}, so memories, approvals, the thread
 * lock, and the scheduler's per-tenant cap all separate by it without any of those stores knowing
 * about HTTP. Their thread names are namespaced too, since the transcript is keyed by thread id
 * alone: user {@code alice} asking for thread {@code work} reads and writes {@code alice:work}.
 *
 * <p>The operator, holder of {@code jclaw.serve-token} or the sole caller when no auth is
 * configured, is the {@code local} tenant: the same one the CLI uses, so what the operator
 * does in a browser and in a terminal is one conversation. The operator may also read any
 * tenant's runs and gates; a user sees only their own.
 */
record Principal(String tenant) {

    /** The tenant the CLI runs as, and the operator over HTTP. */
    static final String LOCAL = TurnScope.local("p", new ThreadId("t")).tenant();

    Principal {
        Objects.requireNonNull(tenant, "tenant");
        if (tenant.isBlank() || tenant.indexOf('/') >= 0 || tenant.indexOf(':') >= 0) {
            throw new IllegalArgumentException("tenant must be non-blank and contain no '/' or ':'");
        }
    }

    static Principal operator() {
        return new Principal(LOCAL);
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
}
