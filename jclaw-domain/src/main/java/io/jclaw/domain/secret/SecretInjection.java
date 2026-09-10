// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.secret;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.secret.SecretVault.Binding;
import io.jclaw.contracts.secret.SecretVault.SecretName;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules for substituting secret references in tool arguments.
 *
 * <p>The model writes {@code {{secret:NAME}}} wherever a credential belongs. This class finds
 * those references, decides whether a binding allows the substitution, and produces the
 * substituted arguments. It touches no vault: the kernel looks values up and passes them in, so
 * the decision is testable with plain strings.
 *
 * <p>The host rule is the one that matters. A secret bound to {@code api.example.com} is
 * substituted only when every absolute URL among the arguments points at a permitted host. The
 * check is over <em>all</em> URL-shaped strings, not a named {@code url} argument, so a tool that
 * takes its target under another name, or a future tool with two, gets the same protection
 * without this class knowing its schema.
 */
public final class SecretInjection {

    /** The reference shape. The name grammar matches {@link SecretName}. */
    public static final Pattern REFERENCE = Pattern.compile("\\{\\{secret:([A-Za-z0-9][A-Za-z0-9_.-]{0,63})\\}\\}");

    private SecretInjection() {
    }

    /** Names referenced anywhere in {@code arguments}, in first-seen order; empty when none. */
    public static Set<SecretName> references(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        Set<SecretName> found = new LinkedHashSet<>();
        walk(arguments, text -> {
            Matcher matcher = REFERENCE.matcher(text);
            while (matcher.find()) {
                found.add(new SecretName(matcher.group(1)));
            }
            return text;
        });
        return java.util.Collections.unmodifiableSet(found);
    }

    /** Hosts of every absolute {@code http(s)} URL among the string arguments. */
    public static Set<String> urlHosts(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        Set<String> hosts = new LinkedHashSet<>();
        walk(arguments, text -> {
            String trimmed = text.trim();
            if (trimmed.regionMatches(true, 0, "http://", 0, 7)
                    || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
                hosts.add(hostOf(trimmed));
            }
            return text;
        });
        return hosts;
    }

    /**
     * The host of an absolute URL, lower-cased, without userinfo or port; empty when the URL has
     * none. Written by hand because the domain has no {@code java.net}, and because the rules are
     * few: authority runs from after {@code ://} to the first {@code /}, {@code ?}, or {@code #};
     * anything before the last {@code @} is userinfo; a bracketed IPv6 literal keeps its brackets'
     * content; otherwise the last {@code :} starts the port.
     */
    static String hostOf(String url) {
        int start = url.indexOf("://");
        if (start < 0) {
            return "";
        }
        String authority = url.substring(start + 3);
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = authority.indexOf(stop);
            if (at >= 0) {
                authority = authority.substring(0, at);
            }
        }
        int userinfo = authority.lastIndexOf('@');
        if (userinfo >= 0) {
            authority = authority.substring(userinfo + 1);
        }
        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            host = close < 0 ? "" : authority.substring(1, close);
        } else {
            int port = authority.lastIndexOf(':');
            host = port >= 0 ? authority.substring(0, port) : authority;
        }
        return host.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Whether {@code binding} allows a secret into an invocation of {@code capability} whose
     * arguments target {@code hosts}.
     *
     * @return empty when permitted, otherwise a stable denial reason
     */
    public static java.util.Optional<String> refuse(Binding binding, CapabilityId capability, Set<String> hosts) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(hosts, "hosts");
        if (!binding.capability().equals(capability)) {
            return java.util.Optional.of("secret_not_bound_to_capability");
        }
        if (hosts.isEmpty()) {
            // No URL to check against means no way to know where the value would go.
            return java.util.Optional.of("secret_requires_target_host");
        }
        for (String host : hosts) {
            if (host.isEmpty() || !binding.permitsHost(host)) {
                return java.util.Optional.of("secret_host_not_allowed");
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Replaces every reference with its value.
     *
     * @param values a value for every referenced name
     * @return the substituted arguments, or the first name that had no value
     */
    public static Result<Map<String, Object>, SecretName> inject(
            Map<String, Object> arguments, Map<SecretName, String> values) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(values, "values");
        for (SecretName name : references(arguments)) {
            if (!values.containsKey(name)) {
                return Result.err(name);
            }
        }
        return Result.ok(walk(arguments, text -> {
            Matcher matcher = REFERENCE.matcher(text);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(values.get(new SecretName(matcher.group(1)))));
            }
            matcher.appendTail(out);
            return out.toString();
        }));
    }

    /** Applies {@code onText} to every string leaf, rebuilding maps and lists around the results. */
    private static Map<String, Object> walk(Map<String, Object> arguments, Function<String, String> onText) {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        arguments.forEach((key, value) -> rebuilt.put(key, walkValue(value, onText)));
        return rebuilt;
    }

    @SuppressWarnings("unchecked")
    private static Object walkValue(Object value, Function<String, String> onText) {
        if (value instanceof String text) {
            return onText.apply(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            map.forEach((k, v) -> rebuilt.put(String.valueOf(k), walkValue(v, onText)));
            return rebuilt;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> walkValue(item, onText)).toList();
        }
        return value;
    }
}
