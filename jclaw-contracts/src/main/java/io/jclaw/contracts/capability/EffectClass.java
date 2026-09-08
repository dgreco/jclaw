package io.jclaw.contracts.capability;

/**
 * What a capability does to the world.
 *
 * <p>Declared per capability and used by policy to decide whether an invocation needs approval.
 * The ordering is meaningful: each constant is at least as dangerous as the one before, so policy
 * can express "auto-approve up to here" as a single comparison rather than a set membership test
 * that silently omits a newly added constant.
 *
 * <p>A capability declares its own effect class in its descriptor, but the declaration is a claim
 * by a manifest, not authority. The host may raise a capability's effect class; a manifest may
 * never lower it below what the host assigns.
 */
public enum EffectClass {

    /** Pure computation. No I/O at all — {@code builtin.time}, {@code builtin.json}. */
    PURE,

    /** Reads inside the workspace. {@code builtin.read_file}, {@code builtin.glob}. */
    READ_LOCAL,

    /** Writes inside the workspace. {@code builtin.write_file}, {@code builtin.apply_patch}. */
    WRITE_LOCAL,

    /** Outbound network. Subject to the egress guard. {@code builtin.http}, {@code builtin.web_fetch}. */
    NETWORK,

    /** Spawns host processes. {@code builtin.shell}. */
    PROCESS,

    /** Irreversible or outside the workspace. Always requires explicit approval. */
    DESTRUCTIVE;

    /** True when this effect is at least as dangerous as {@code threshold}. */
    public boolean atLeast(EffectClass threshold) {
        return compareTo(threshold) >= 0;
    }

    /** Effects that never escape the host and so need no egress mediation. */
    public boolean isLocal() {
        return this == PURE || this == READ_LOCAL || this == WRITE_LOCAL;
    }

    /**
     * Whether an invocation of this class may be replayed after an ambiguous crash without risking
     * a duplicated external effect. Only genuinely read-only work qualifies.
     */
    public boolean isReplaySafe() {
        return this == PURE || this == READ_LOCAL;
    }
}
