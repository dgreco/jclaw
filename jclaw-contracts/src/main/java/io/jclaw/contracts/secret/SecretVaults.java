package io.jclaw.contracts.secret;

import java.util.Objects;

/**
 * One vault per tenant.
 *
 * <p>Every scope-keyed store already separates by tenant, and a vault was the exception: one set
 * of credentials for whoever the run belonged to. That is right for the single-user CLI, where
 * there is one tenant and the distinction is noise, and wrong the moment {@code serve} has users
 * — a secret named {@code api-key} would mean whichever tenant wrote it last, and every tenant
 * could spend it.
 *
 * <p>The lookup takes the tenant from the invocation's {@link io.jclaw.contracts.turn.TurnScope},
 * so a tenant cannot name another's vault: it never supplies the tenant, the scope does, and the
 * scope was fixed at admission.
 *
 * <p>{@link #shared} is the single-vault arrangement expressed through the same port, which is
 * what the CLI uses. It exists so the kernel has one code path rather than a null check.
 */
@FunctionalInterface
public interface SecretVaults {

    /** The vault holding {@code tenant}'s credentials. Never null; an unknown tenant gets an empty one. */
    SecretVault forTenant(String tenant);

    /** Every tenant shares one vault: correct for the CLI, where there is only ever one. */
    static SecretVaults shared(SecretVault vault) {
        Objects.requireNonNull(vault, "vault");
        return tenant -> vault;
    }

    /** No vault at all. */
    static SecretVaults empty() {
        return shared(SecretVault.empty());
    }
}
