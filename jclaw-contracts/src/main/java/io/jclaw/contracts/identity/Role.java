package io.jclaw.contracts.identity;

/**
 * What a signed-in person may do.
 *
 * <p>Three roles, ordered, because the useful distinctions in a harness like this are few: watch
 * it, use it, or run it. Anything finer would be policy dressed as identity, and policy already
 * has a home in {@code CapabilityPolicy}.
 *
 * <p>A role is about the product surface, never about the kernel. A member cannot approve their
 * way past a capability ceiling by being a member; the authority gate is unchanged and asks the
 * same questions of everyone.
 */
public enum Role {

    /** May read threads, runs, and traces. May not start a turn or answer a gate. */
    VIEWER,

    /** May start turns and answer gates on their own runs. The ordinary role. */
    MEMBER,

    /** May do all of that, for every tenant, and mint sessions for others. */
    OPERATOR;

    /** Whether this role includes everything {@code other} may do. */
    public boolean includes(Role other) {
        return compareTo(other) >= 0;
    }

    public boolean canWrite() {
        return includes(MEMBER);
    }

    public boolean canAdminister() {
        return this == OPERATOR;
    }
}
