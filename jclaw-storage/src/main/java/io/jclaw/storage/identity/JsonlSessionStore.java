package io.jclaw.storage.identity;

import io.jclaw.contracts.identity.Role;
import io.jclaw.contracts.identity.SessionStore;
import io.jclaw.storage.rows.RowStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sessions as rows, with only the hash of each token stored.
 *
 * <p>The token is 32 bytes from {@link SecureRandom}, handed back once and never written down.
 * What the file holds is its SHA-256, the user, the role, and the two instants, so an operator
 * reading the store learns who is signed in and until when, and cannot become any of them.
 *
 * <p>Lookup hashes the presented token and compares digests, so the cost is one hash rather than
 * a scan of secrets, and there is no comparison against a stored credential to get wrong.
 */
public final class JsonlSessionStore implements SessionStore {

    private static final String KIND_ISSUED = "issued";
    private static final String KIND_REVOKED = "revoked";

    private final RowStore rows;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public JsonlSessionStore(RowStore rows, Clock clock) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized Session issue(String user, Role role, Duration ttl) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("a session ttl must be positive");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Instant now = clock.instant();
        Session session = new Session(user, role, now, now.plus(ttl), Optional.of(token));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_ISSUED);
        row.put("hash", hash(token));
        row.put("user", user);
        row.put("role", role.name());
        row.put("issuedAt", session.issuedAt().toString());
        row.put("expiresAt", session.expiresAt().toString());
        rows.append(row);
        return session;
    }

    @Override
    public synchronized Optional<Session> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(replay().get(hash(token)))
                .filter(session -> session.isLiveAt(clock.instant()));
    }

    @Override
    public synchronized boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hashed = hash(token);
        Session session = replay().get(hashed);
        if (session == null || !session.isLiveAt(clock.instant())) {
            return false;
        }
        appendRevocation(hashed);
        return true;
    }

    @Override
    public synchronized int revokeAllFor(String user) {
        Objects.requireNonNull(user, "user");
        Instant now = clock.instant();
        int revoked = 0;
        for (Map.Entry<String, Session> entry : replay().entrySet()) {
            if (entry.getValue().user().equals(user) && entry.getValue().isLiveAt(now)) {
                appendRevocation(entry.getKey());
                revoked++;
            }
        }
        return revoked;
    }

    @Override
    public synchronized List<Session> active() {
        Instant now = clock.instant();
        List<Session> live = new ArrayList<>();
        replay().values().stream().filter(session -> session.isLiveAt(now)).forEach(live::add);
        return List.copyOf(live);
    }

    private void appendRevocation(String hashed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_REVOKED);
        row.put("hash", hashed);
        row.put("at", clock.instant().toString());
        rows.append(row);
    }

    private Map<String, Session> replay() {
        Map<String, Session> live = new LinkedHashMap<>();
        for (Map<String, Object> row : rows.readAll()) {
            String hashed = String.valueOf(row.get("hash"));
            switch (String.valueOf(row.get("kind"))) {
                case KIND_ISSUED -> {
                    try {
                        live.put(hashed, new Session(
                                String.valueOf(row.get("user")),
                                Role.valueOf(String.valueOf(row.get("role"))),
                                Instant.parse(String.valueOf(row.get("issuedAt"))),
                                Instant.parse(String.valueOf(row.get("expiresAt"))),
                                Optional.empty()));
                    } catch (RuntimeException e) {
                        // A damaged row loses one session, not everyone's.
                    }
                }
                case KIND_REVOKED -> live.remove(hashed);
                default -> { }
            }
        }
        return live;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
