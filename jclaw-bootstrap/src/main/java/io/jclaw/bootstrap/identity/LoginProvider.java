// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.identity;

import java.util.Objects;
import java.util.Optional;

/**
 * The configured OpenID Connect provider, or the fact that there is none.
 *
 * <p>A wrapper this thin looks like ceremony, and on the JVM it would be: a {@code @Bean} method
 * could simply return {@code Optional<OidcLogin>}. It cannot, and the reason is worth recording
 * because the failure is invisible until the native image runs.
 *
 * <p>Under AOT — how the native image is built — beans come from a generated instance supplier
 * rather than a reflective factory-method call, and {@code obtainFromSupplier} wraps whatever the
 * supplier returns in a {@code BeanWrapperImpl}. That constructor calls
 * {@code ObjectUtils.unwrapOptional} before asserting the target is non-null, so an empty
 * {@code Optional} becomes {@code null} and fails the assertion — taking the entire application
 * context down on every command, not just the ones that sign people in. A present one is worse in
 * its way: it is silently unwrapped, and the bean registered under the method's declared type is
 * the value inside. The reflective path on the JVM does neither, which is why the whole test
 * suite passes while the binary cannot start.
 *
 * <p>Being a record, not an {@code Optional}, this survives both paths unchanged. The
 * architecture test forbidding {@code Optional}-returning bean methods is what keeps the lesson
 * from having to be learned twice.
 */
public record LoginProvider(Optional<OidcLogin> login) {

    public LoginProvider {
        Objects.requireNonNull(login, "login");
    }

    /** No provider configured: the login routes are a 404. */
    public static LoginProvider none() {
        return new LoginProvider(Optional.empty());
    }

    public static LoginProvider of(OidcLogin login) {
        return new LoginProvider(Optional.of(Objects.requireNonNull(login, "login")));
    }

    public boolean configured() {
        return login.isPresent();
    }

    /** The provider, or {@code null} for the call sites that take a nullable one. */
    public OidcLogin orNull() {
        return login.orElse(null);
    }
}
