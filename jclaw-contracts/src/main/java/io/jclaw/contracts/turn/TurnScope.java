// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.contracts.turn;

import java.util.Objects;

/**
 * Canonical tenant/agent/project/thread scope.
 *
 * <p>This is the isolation key and the active-thread lock key: at most one run may execute
 * against a given scope at a time, and that exclusion is established before any model or tool
 * side effect. Two turns sharing a scope contend; two turns differing in any component do not.
 *
 * @param tenant  owning tenant, {@code "local"} for standalone use
 * @param agent   agent identity within the tenant
 * @param project project or workspace discriminator
 * @param thread  conversation thread
 */
public record TurnScope(String tenant, String agent, String project, ThreadId thread) {

    public TurnScope {
        tenant = requireToken(tenant, "tenant");
        agent = requireToken(agent, "agent");
        project = requireToken(project, "project");
        Objects.requireNonNull(thread, "thread");
    }

    /** The standalone single-user scope used by the CLI when no tenant is configured. */
    public static TurnScope local(String project, ThreadId thread) {
        return new TurnScope("local", "default", project, thread);
    }

    /**
     * Stable string form of the lock key. Deliberately excludes nothing: the whole scope is
     * the key, so callers cannot accidentally narrow it and defeat the exclusion.
     */
    public String lockKey() {
        return tenant + '/' + agent + '/' + project + '/' + thread.value();
    }

    private static String requireToken(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.indexOf('/') >= 0) {
            throw new IllegalArgumentException(field + " must not contain '/': " + trimmed);
        }
        return trimmed;
    }
}
