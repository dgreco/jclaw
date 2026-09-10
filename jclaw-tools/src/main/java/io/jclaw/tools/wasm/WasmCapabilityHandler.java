// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.tools.wasm;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.CapabilityHandler;
import io.jclaw.contracts.capability.CapabilityId;
import io.jclaw.contracts.capability.CapabilityInvocation;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.HandlerError;
import io.jclaw.contracts.capability.TrustClass;
import io.jclaw.tools.Schemas;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One tool of a WebAssembly extension, as a capability.
 *
 * <p>The module goes through the same authority gate as everything else, which is the point: a
 * sandbox decides what code can reach, and the kernel decides whether it may. Being in a sandbox
 * earns a WASM tool nothing at the gate.
 *
 * <p>The host functions the module may call are bridged here to the lane's own
 * {@code HandlerContext}, so a module reading a file goes through the workspace guard and one
 * fetching a URL goes through the egress guard, the same as a built-in would. A module cannot
 * reach past them because the only doors it has are these.
 */
public final class WasmCapabilityHandler implements CapabilityHandler {

    private static final int MAX_FETCH_BYTES = 64 * 1024;

    /** Redirects are never followed: each hop would need the guard's opinion, and this has one. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final WasmLane lane;
    private final String tool;
    private final CapabilityDescriptor descriptor;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public WasmCapabilityHandler(
            String extension, String tool, String description, WasmLane lane,
            TrustClass trust, EffectClass effect) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.descriptor = new CapabilityDescriptor(
                CapabilityId.of(idFor(extension, tool)),
                "[via WASM extension '" + extension + "'] "
                        + (description == null || description.isBlank() ? tool : description),
                Schemas.object(Map.of(), List.of()),
                Objects.requireNonNull(effect, "effect"),
                Objects.requireNonNull(trust, "trust"));
    }

    /** Namespaced id, so a module cannot present itself as a built-in. */
    public static String idFor(String extension, String tool) {
        return "wasm." + sanitize(extension) + "." + sanitize(tool);
    }

    private static String sanitize(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0)) ? "s" + cleaned : cleaned;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", tool);
        call.put("arguments", invocation.arguments());
        return lane.call(mapper.writeValueAsString(call), services(context)).mapErr(HandlerError::failed);
    }

    /** The module's view of the host: the same guards a built-in gets, and nothing more. */
    private WasmLane.HostServices services(HandlerContext context) {
        return new WasmLane.HostServices() {
            @Override
            public void log(String message) {
                // Collected by the lane and returned with the result; there is nowhere else for a
                // module's output to go that the audit log would not have to be told about.
            }

            @Override
            public Optional<String> readFile(String path) {
                if (context == null) {
                    return Optional.empty();
                }
                return context.resolvePath(path).toOptional().flatMap(resolved -> {
                    try {
                        return Optional.of(Files.readString(resolved, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                });
            }

            @Override
            public Optional<String> httpGet(String url) {
                if (context == null) {
                    return Optional.empty();
                }
                // Guarded first, fetched second, bounded third. A module never holds the client.
                return context.checkEgress(url).toOptional().flatMap(uri -> {
                    try {
                        var response = HTTP.send(HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(15))
                                        .header("User-Agent", "jclaw-wasm/0.1")
                                        .GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() >= 300) {
                            return Optional.empty();
                        }
                        String body = response.body();
                        return Optional.of(body.length() > MAX_FETCH_BYTES
                                ? body.substring(0, MAX_FETCH_BYTES) : body);
                    } catch (IOException e) {
                        return Optional.empty();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }
                });
            }
        };
    }
}
