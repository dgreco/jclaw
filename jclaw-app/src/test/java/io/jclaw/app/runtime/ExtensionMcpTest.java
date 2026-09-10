// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.runtime;

import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.contracts.capability.EffectClass;
import io.jclaw.contracts.capability.TrustClass;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.domain.extension.ExtensionSignature;
import io.jclaw.storage.extension.FilesystemExtensionRegistry;
import io.jclaw.storage.extension.PublisherKeys;
import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.mcp.JsonlMcpServerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An installed MCP extension's tools carry the trust the installation earned: a verified
 * package's declared effect class is honoured, an unsigned package's tools are NETWORK and
 * COMMUNITY whatever its manifest claims.
 */
class ExtensionMcpTest {

    @TempDir Path dir;

    @Test
    @DisplayName("verified extensions get their declared effect; community ones do not")
    void trustFlowsToTools() throws IOException {
        Path server = dir.resolve("fake-mcp");
        Files.writeString(server, "#!/bin/sh\n"
                + "while IFS= read -r line; do\n"
                + "  id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]*\\).*/\\1/p')\n"
                + "  case \"$line\" in\n"
                + "    *'\"initialize\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"f\",\"version\":\"0\"}}}\\n' \"$id\";;\n"
                + "    *'\"tools/list\"'*) printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"lookup\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}\\n' \"$id\";;\n"
                + "  esac\n"
                + "done\n");
        Files.setPosixFilePermissions(server, PosixFilePermissions.fromString("rwxr-xr-x"));

        PublisherKeys.Pair acme = PublisherKeys.generate();
        FilesystemExtensionRegistry extensions = new FilesystemExtensionRegistry(dir.resolve("ext"),
                new JsonlFile(dir.resolve("ext.jsonl")), Map.of("acme", acme.publicKey()), Optional.empty(),
                Clock.systemUTC());

        Path verified = pkg("verified", "acme", server);
        Files.writeString(verified.resolve("jclaw-extension.sig"),
                ExtensionSignature.sign(extensions.digestOf(verified).orElseThrow(), acme.privateKey()));
        extensions.install(verified, Map.of()).orElseThrow();
        extensions.install(pkg("community", null, server), Map.of()).orElseThrow();

        McpRegistry registry = new McpRegistry(new JsonlMcpServerStore(new JsonlFile(dir.resolve("mcp.jsonl"))),
                dir, Optional.empty(), extensions);
        try {
            Map<String, CapabilityDescriptor> byId = new java.util.TreeMap<>();
            registry.handlers().forEach(h -> byId.put(h.descriptor().id().value(), h.descriptor()));
            assertEquals(List.of("mcp.community.lookup", "mcp.verified.lookup"), List.copyOf(byId.keySet()));

            assertEquals(TrustClass.VERIFIED, byId.get("mcp.verified.lookup").trust());
            assertEquals(EffectClass.READ_LOCAL, byId.get("mcp.verified.lookup").effect());
            assertEquals(TrustClass.COMMUNITY, byId.get("mcp.community.lookup").trust());
            assertEquals(EffectClass.NETWORK, byId.get("mcp.community.lookup").effect(),
                    "an unverified manifest cannot talk its way down the effect ladder");
        } finally {
            registry.shutdown();
        }
    }

    private Path pkg(String name, String publisher, Path server) throws IOException {
        Path p = Files.createDirectories(dir.resolve("pkg-" + name));
        Files.writeString(p.resolve("jclaw-extension.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1\",\"kind\":\"mcp\",\"command\":[\"" + server + "\"],"
                        + "\"effect\":\"read_local\"" + (publisher == null ? "" : ",\"publisher\":\"" + publisher + "\"") + "}");
        return p;
    }
}
