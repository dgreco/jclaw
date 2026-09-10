// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.extension;

import com.sun.net.httpserver.HttpServer;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.domain.extension.PackageDigest;
import io.jclaw.kernel.guard.EgressGuard;
import io.jclaw.storage.extension.FilesystemExtensionRegistry;
import io.jclaw.storage.jsonl.JsonlFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A registry is a static index and some archives, and everything it says is checked.
 *
 * <p>The interesting cases are the dishonest ones: an index that advertises a digest the archive
 * does not have, and an archive whose entry names try to write outside the directory it is being
 * unpacked into. Both must be refused before the ordinary install path — which is what decides
 * trust — ever sees the package.
 */
class ExtensionCatalogTest {

    private static HttpServer server;
    private static String base;

    /** What the registry currently serves, by path. */
    private static final Map<String, byte[]> served = new LinkedHashMap<>();

    @TempDir Path dir;

    @BeforeAll
    static void startRegistry() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopRegistry() {
        server.stop(0);
    }

    /** The guard has to allow loopback for a test registry; that is the only reason it is here. */
    private ExtensionCatalog catalog() {
        return new ExtensionCatalog(List.of(base), EgressGuard.allowingPrivateNetworks());
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String digestOf(Map<String, String> files) {
        Map<String, byte[]> bytes = new TreeMap<>();
        files.forEach((path, content) -> bytes.put(path, content.getBytes(StandardCharsets.UTF_8)));
        bytes.remove("jclaw-extension.sig");
        return PackageDigest.of(bytes);
    }

    private static Map<String, String> skillPackage(String name, String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("jclaw-extension.json",
                "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"kind\":\"skill\","
                        + "\"description\":\"formats a changelog\"}");
        files.put("SKILL.md", "---\nname: " + name + "\ndescription: d\nwhen-to-use: w\n---\nBody.\n");
        return files;
    }

    private static void publish(String name, String version, String digest) throws IOException {
        Map<String, String> files = skillPackage(name, version);
        served.put("/" + name + "-" + version + ".zip", zip(files));
        served.put("/index.json", ("{\"packages\":[{"
                + "\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"kind\":\"skill\","
                + "\"description\":\"formats a changelog\","
                + "\"url\":\"" + base + "/" + name + "-" + version + ".zip\","
                + "\"digest\":\"" + (digest == null ? digestOf(files) : digest) + "\"}]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private FilesystemExtensionRegistry registry() {
        return new FilesystemExtensionRegistry(dir.resolve("extensions"),
                new JsonlFile(dir.resolve("extensions.jsonl")), Map.of(), Optional.empty(), Clock.systemUTC());
    }

    @Test
    @DisplayName("a package is found, fetched, and installs down the ordinary path")
    void fetchAndInstall() throws IOException {
        publish("changelog", "1.2.0", null);
        ExtensionCatalog catalog = catalog();

        List<ExtensionCatalog.Listing> found = catalog.search("changelog");
        assertEquals(1, found.size());
        assertEquals("changelog@1.2.0", found.get(0).coordinate());
        assertEquals(base, found.get(0).registry());
        assertEquals(found, catalog.search("CHANGELOG"), "search is case-insensitive");
        assertTrue(catalog.search("nothing-like-this").isEmpty());

        Path unpacked = catalog.fetch(found.get(0), dir.resolve("downloads")).orElseThrow();
        assertTrue(Files.exists(unpacked.resolve("jclaw-extension.json")));

        ExtensionRegistry.Installed installed = registry().install(unpacked, Map.of()).orElseThrow();
        assertEquals("changelog", installed.name());
        assertEquals("1.2.0", installed.manifest().version());
        assertEquals(io.jclaw.contracts.capability.TrustClass.COMMUNITY, installed.trust(),
                "coming from a registry earns a package nothing; only a signature does");
    }

    @Test
    @DisplayName("an index that lies about the digest gets nothing installed")
    void digestMustMatch() throws IOException {
        publish("liar", "1.0.0", "0".repeat(64));
        ExtensionCatalog catalog = catalog();
        var listing = catalog.resolve("liar", Optional.empty()).orElseThrow();
        assertEquals("digest_mismatch",
                catalog.fetch(listing, dir.resolve("downloads")).errorAsOptional().orElseThrow());
    }

    @Test
    @DisplayName("an archive entry that climbs out of the directory is refused")
    void zipSlip() throws IOException {
        Map<String, String> hostile = new LinkedHashMap<>();
        hostile.put("../../escaped.txt", "owned");
        served.put("/hostile-1.0.zip", zip(hostile));
        served.put("/index.json", ("{\"packages\":[{\"name\":\"hostile\",\"version\":\"1.0\","
                + "\"kind\":\"skill\",\"url\":\"" + base + "/hostile-1.0.zip\",\"digest\":\"\"}]}")
                .getBytes(StandardCharsets.UTF_8));

        ExtensionCatalog catalog = catalog();
        var listing = catalog.resolve("hostile", Optional.empty()).orElseThrow();
        assertEquals("archive_entry_escapes_directory",
                catalog.fetch(listing, dir.resolve("downloads")).errorAsOptional().orElseThrow());
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    @DisplayName("the newest version wins, an exact one can be asked for, and neither is a string sort")
    void versionOrdering() throws IOException {
        Map<String, String> old = skillPackage("tool", "1.9.0");
        Map<String, String> recent = skillPackage("tool", "1.10.0");
        served.put("/tool-1.9.0.zip", zip(old));
        served.put("/tool-1.10.0.zip", zip(recent));
        served.put("/index.json", ("{\"packages\":["
                + "{\"name\":\"tool\",\"version\":\"1.9.0\",\"kind\":\"skill\",\"url\":\""
                + base + "/tool-1.9.0.zip\",\"digest\":\"" + digestOf(old) + "\"},"
                + "{\"name\":\"tool\",\"version\":\"1.10.0\",\"kind\":\"skill\",\"url\":\""
                + base + "/tool-1.10.0.zip\",\"digest\":\"" + digestOf(recent) + "\"}]}")
                .getBytes(StandardCharsets.UTF_8));

        ExtensionCatalog catalog = catalog();
        assertEquals("1.10.0", catalog.resolve("tool", Optional.empty()).orElseThrow().version(),
                "1.10 is newer than 1.9, which a string sort gets backwards");
        assertEquals("1.9.0", catalog.resolve("tool", Optional.of("1.9.0")).orElseThrow().version());
        assertTrue(catalog.resolve("tool", Optional.of("2.0.0")).isEmpty());

        assertTrue(ExtensionCatalog.isNewer("1.10.0", "1.9.0"));
        assertFalse(ExtensionCatalog.isNewer("1.9.0", "1.10.0"));
        assertFalse(ExtensionCatalog.isNewer("1.2.0", "1.2.0"), "the same version is not an upgrade");
        assertTrue(ExtensionCatalog.isNewer("2.0", "1.99.99"));
    }

    @Test
    @DisplayName("an upgrade is offered only when the registry is strictly newer")
    void upgradeDetection() throws IOException {
        publish("changelog", "1.2.0", null);
        FilesystemExtensionRegistry extensions = registry();
        ExtensionCatalog catalog = catalog();
        Path unpacked = catalog.fetch(
                catalog.resolve("changelog", Optional.empty()).orElseThrow(),
                dir.resolve("downloads")).orElseThrow();
        extensions.install(unpacked, Map.of()).orElseThrow();

        assertTrue(catalog.upgradesFor(extensions.list()).isEmpty(),
                "the version already installed is not an upgrade");

        publish("changelog", "1.10.0", null);
        var upgrades = catalog.upgradesFor(extensions.list());
        assertEquals(1, upgrades.size());
        assertEquals("1.2.0", upgrades.get(0).installed().manifest().version());
        assertEquals("1.10.0", upgrades.get(0).listing().version());

        publish("changelog", "1.1.0", null);
        assertTrue(catalog.upgradesFor(extensions.list()).isEmpty(),
                "a registry that rolls back offers no upgrade");
    }

    @Test
    @DisplayName("an unreachable registry is skipped, and a guarded URL is never fetched")
    void unreachableAndGuarded() {
        assertTrue(new ExtensionCatalog(List.of("http://127.0.0.1:1/registry"),
                        EgressGuard.allowingPrivateNetworks()).list().isEmpty(),
                "one broken mirror must not hide the others, so it is skipped rather than fatal");

        // The default guard refuses loopback, so the same registry is unreachable for a second
        // reason: the URL never leaves the process.
        ExtensionCatalog guarded = new ExtensionCatalog(List.of(base), EgressGuard.publicOnly());
        assertTrue(guarded.list().isEmpty());
        assertTrue(guarded.configured(), "configured is about the operator's setting, not reachability");
    }
}
