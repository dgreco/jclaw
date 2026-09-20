// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Sessions issued to signed-in people.
 *
 * <p>A session is what a login produces and a bearer token is not: it names who, it carries a
 * role, it expires, and it can be revoked. The static tokens in {@code jclaw.serve-users} remain
 * for machines, which have no login to perform and no session to end.
 *
 * <p>Implementations must store only a hash of the token. A session store that can be read back
 * into live credentials is a file that grants access to everyone who can read it, and the whole
 * point of a session is that losing the store does not lose the account.
 */
public interface SessionStore {

    /**
     * An issued session. The token itself is present only on {@link #issue}, which is the one
     * moment it exists in the clear.
     */
    record Session(String user, Role role, Instant issuedAt, Instant expiresAt, Optional<String> token) {

        public Session {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(token, "token");
            if (user.isBlank()) {
                throw new IllegalArgumentException("a session needs a user");
            }
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("a session must expire after it is issued");
            }
        }

        public boolean isLiveAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    /** Issues a session and returns it with its token, the only time the token is readable. */
    Session issue(String user, Role role, Duration ttl);

    /** The live session for a presented token, or empty when unknown, revoked, or expired. */
    Optional<Session> find(String token);

    /** Ends one session by its token. Returns whether a live one existed. */
    boolean revoke(String token);

    /** Ends every session for a user, for when an account is removed. Returns how many. */
    int revokeAllFor(String user);

    /** Sessions that have not expired, without their tokens. */
    List<Session> active();
}
