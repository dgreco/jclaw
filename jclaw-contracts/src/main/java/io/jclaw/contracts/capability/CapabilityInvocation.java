package io.jclaw.contracts.capability;

import io.jclaw.contracts.turn.TurnRunId;
import io.jclaw.contracts.turn.TurnScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One exact request to invoke a capability.
 *
 * <p>"Exact" is the operative word. Approval leases in IronClaw are scoped to a specific
 * invocation, not to a capability in general — approving one {@code shell} command must not
 * silently approve the next one. {@link #fingerprint()} is what makes that enforceable: it hashes
 * the capability together with its arguments, so a human approving a fingerprint is approving
 * precisely the call they were shown.
 *
 * @param callId provider-assigned tool-call id, used to correlate the result back to the model
 * @param scope  the run's scope; carried so authorization never has to be told separately
 * @param run    the run this invocation belongs to, recorded as result provenance. Excluded
 *               from {@link #fingerprint()} — the same call in a later run is the same call
 *               and must match an existing approval rather than silently re-prompting.
 */
public record CapabilityInvocation(
        CapabilityId capability,
        String callId,
        Map<String, Object> arguments,
        TurnScope scope,
        TurnRunId run) {

    /**
     * ASCII unit separator, used to delimit fields while hashing. Written as a numeric cast rather
     * than a literal control byte so this source file stays pure ASCII: an invisible 0x1F in a
     * char literal is unreadable in review and easy for tooling to mangle silently.
     */
    private static final char FIELD_SEPARATOR = (char) 0x1F;

    public CapabilityInvocation {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(callId, "callId");
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(run, "run");
    }

    /**
     * Stable hash of capability plus arguments.
     *
     * <p>Two invocations share a fingerprint exactly when they would do the same thing, so an
     * approval granted for one is safe to honour for the other. Arguments are sorted before
     * hashing so map iteration order cannot change the identity of an otherwise identical call,
     * and fields are delimited by a separator that cannot occur in ordinary argument text — so
     * {@code {a: "b", c: "d"}} cannot collide with {@code {a: "b=c", c: "d"}}.
     *
     * <p>The scope is deliberately excluded: a fingerprint identifies <em>what</em> is being done,
     * and the authorization layer checks the scope separately. Folding scope in here would let an
     * approval look distinct when the dangerous part is identical.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder(capability.value()).append(FIELD_SEPARATOR);
        new TreeMap<>(arguments).forEach((key, value) -> canonical
                .append(key)
                .append('=')
                .append(value)
                .append(FIELD_SEPARATOR));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Reads a string argument, or returns {@code fallback} when absent or not a string. */
    public String stringArg(String key, String fallback) {
        return arguments.get(key) instanceof String s ? s : fallback;
    }

    /** Reads an int argument, tolerating the numeric types a JSON parser may produce. */
    public int intArg(String key, int fallback) {
        return arguments.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    /** Reads a boolean argument, or returns {@code fallback}. */
    public boolean boolArg(String key, boolean fallback) {
        return arguments.get(key) instanceof Boolean b ? b : fallback;
    }
}
