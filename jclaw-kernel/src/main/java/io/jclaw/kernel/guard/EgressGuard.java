// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.kernel.guard;

import io.jclaw.contracts.Result;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Host-mediated outbound network policy. Every outbound URL passes through here.
 *
 * <p>The threat is server-side request forgery: an agent that can be talked into fetching a URL is
 * an agent that can be talked into fetching {@code http://169.254.169.254/latest/meta-data/} and
 * handing back cloud credentials, or probing {@code http://localhost:6379} to reach an unguarded
 * service. Prompt injection makes this a live concern rather than a theoretical one — the attacker
 * controls the page, and the page controls the next request.
 *
 * <p>Defences, all applied:
 * <ul>
 *   <li>scheme allowlist — {@code http} and {@code https} only, so no {@code file://} reads;</li>
 *   <li>no embedded credentials, which would otherwise be logged or leak into redirects;</li>
 *   <li><b>every</b> resolved address is checked, not just the first. A hostname resolving to one
 *       public and one private address must be rejected, or the check is trivially bypassed;</li>
 *   <li>loopback, link-local, site-local, unique-local, multicast, and wildcard addresses denied.</li>
 * </ul>
 *
 * <p>One honest limitation, stated rather than hidden: this validates at check time, and DNS can
 * change between the check and the connection (a DNS-rebinding race). Closing that fully requires
 * pinning the validated address into the connection itself. The HTTP adapter re-validates the final
 * address after redirects, which narrows the window; it does not eliminate it.
 */
public final class EgressGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Cloud metadata endpoints, denied by name as well as by address family. */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "169.254.169.254");

    private final boolean allowPrivateNetworks;
    private final Set<String> hostAllowlist;
    private final Set<String> hostDenylist;

    private EgressGuard(boolean allowPrivateNetworks, Set<String> hostAllowlist, Set<String> hostDenylist) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.hostAllowlist = Set.copyOf(hostAllowlist);
        this.hostDenylist = Set.copyOf(hostDenylist);
    }

    /** The default policy: public internet only. */
    public static EgressGuard publicOnly() {
        return new EgressGuard(false, Set.of(), Set.of());
    }

    /**
     * Restricts egress to an explicit set of hosts. The strictest useful posture, and the right
     * default for an untrusted extension.
     */
    public static EgressGuard allowlist(Set<String> hosts) {
        return new EgressGuard(false, hosts, Set.of());
    }

    /**
     * Permits private-network destinations.
     *
     * <p>Only for local development against a service on the loopback interface — an Ollama daemon,
     * say. Never appropriate in a hosted deployment, where it re-opens the SSRF hole this class
     * exists to close.
     */
    public static EgressGuard allowingPrivateNetworks() {
        return new EgressGuard(true, Set.of(), Set.of());
    }

    /**
     * Restricts egress to these hosts on top of the current posture. Entries are exact hosts or
     * {@code *.suffix} wildcards, matched case-insensitively; the private-network and metadata
     * checks still apply to whatever is allowed, so an allowlist can only narrow, never widen.
     */
    public EgressGuard withAllowlist(Set<String> hosts) {
        return new EgressGuard(allowPrivateNetworks, normalize(hosts), hostDenylist);
    }

    public EgressGuard withDenylist(Set<String> hosts) {
        return new EgressGuard(allowPrivateNetworks, hostAllowlist, normalize(hosts));
    }

    private static Set<String> normalize(Set<String> hosts) {
        Objects.requireNonNull(hosts, "hosts");
        return hosts.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Exact match, or a {@code *.suffix} entry matching any host below that suffix. */
    private static boolean matches(Set<String> entries, String host) {
        if (entries.contains(host)) {
            return true;
        }
        for (String entry : entries) {
            if (entry.startsWith("*.") && host.endsWith(entry.substring(1))
                    && host.length() > entry.length() - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates a URL for outbound use.
     *
     * @return the parsed URI, or a stable denial reason suitable for an event
     */
    public Result<URI, String> check(String url) {
        Objects.requireNonNull(url, "url");

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return Result.err("url_malformed");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return Result.err("scheme_not_allowed");
        }
        if (uri.getUserInfo() != null) {
            return Result.err("url_contains_credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Result.err("url_missing_host");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);

        if (BLOCKED_HOSTS.contains(normalizedHost) || matches(hostDenylist, normalizedHost)) {
            return Result.err("host_denied");
        }
        if (!hostAllowlist.isEmpty() && !matches(hostAllowlist, normalizedHost)) {
            return Result.err("host_not_in_allowlist");
        }

        if (!allowPrivateNetworks) {
            Result<URI, String> addressCheck = checkAddresses(normalizedHost, uri);
            if (addressCheck.isErr()) {
                return addressCheck;
            }
        }
        return Result.ok(uri);
    }

    /**
     * Resolves the host and rejects if <em>any</em> address is non-public.
     *
     * <p>Checking only the first address is a real and commonly exploited bypass: an attacker
     * controls their own DNS and can return {@code [1.2.3.4, 127.0.0.1]}.
     */
    private Result<URI, String> checkAddresses(String host, URI uri) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Result.err("host_unresolvable");
        }
        if (addresses.length == 0) {
            return Result.err("host_unresolvable");
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return Result.err("private_address_denied");
            }
        }
        return Result.ok(uri);
    }

    /** Whether an address belongs to a range that must never be reachable from an agent tool. */
    public static boolean isPrivate(InetAddress address) {
        Objects.requireNonNull(address, "address");
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            // 100.64.0.0/10 carrier-grade NAT — routable-looking but internal.
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 192.0.0.0/24 IETF protocol assignments.
            if (first == 192 && second == 0 && (octets[2] & 0xFF) == 0) {
                return true;
            }
        } else if (octets.length == 16) {
            // fc00::/7 unique local addresses.
            if ((octets[0] & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }

    /** Hosts explicitly permitted, empty when the guard is not in allowlist mode. */
    public List<String> allowlistedHosts() {
        return List.copyOf(hostAllowlist);
    }

    /** Hosts explicitly denied beyond the built-in metadata endpoints. */
    public List<String> denylistedHosts() {
        return List.copyOf(hostDenylist);
    }

    public boolean privateNetworksAllowed() {
        return allowPrivateNetworks;
    }
}
