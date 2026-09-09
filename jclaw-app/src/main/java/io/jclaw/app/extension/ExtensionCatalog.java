package io.jclaw.app.extension;

import io.jclaw.contracts.Result;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.domain.extension.PackageDigest;
import io.jclaw.kernel.guard.EgressGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extensions available from somewhere other than a directory on this machine.
 *
 * <p>A registry is a plain document at {@code <base>/index.json} listing packages, versions, and
 * where to download each. That is deliberately the least interesting design available: no
 * protocol, no account, no server to run. A registry is a static file, which means anyone can
 * publish one and nothing has to stay up but a web server.
 *
 * <p>What arrives is not trusted for being in a registry. The archive is unpacked into a
 * temporary directory, the package digest is recomputed and compared with what the index
 * promised, and only then is the ordinary install path used, which verifies the signature and
 * decides trust exactly as it would for a local directory. An index that lies about a digest is
 * caught before the package is installed, and an index that omits one gets no trust for saying
 * nothing.
 *
 * <p>Registry URLs go through the egress guard. A registry is operator configuration, but it is
 * still a URL this process is about to fetch and unpack.
 */
public final class ExtensionCatalog {

    private static final Logger log = LoggerFactory.getLogger(ExtensionCatalog.class);

    /** Room enough for a real package, small enough that a hostile one cannot fill the disk. */
    private static final long MAX_ARCHIVE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2048;

    /** One package as a registry advertises it. */
    public record Listing(
            String name, String version, String kind, String description,
            String url, String digest, Optional<String> publisher, String registry) {

        public Listing {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(publisher, "publisher");
            Objects.requireNonNull(registry, "registry");
        }

        public String coordinate() {
            return name + "@" + version;
        }
    }

    private final List<String> registries;
    private final EgressGuard egress;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ExtensionCatalog(List<String> registries, EgressGuard egress) {
        this.registries = List.copyOf(Objects.requireNonNull(registries, "registries"));
        this.egress = Objects.requireNonNull(egress, "egress");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean configured() {
        return !registries.isEmpty();
    }

    public List<String> registries() {
        return registries;
    }

    /**
     * Everything every configured registry lists, newest version of each name first.
     *
     * <p>A registry that is unreachable is skipped with a line in the log rather than failing the
     * search, since one broken mirror should not hide the others.
     */
    @SuppressWarnings("unchecked")
    public List<Listing> list() {
        List<Listing> found = new ArrayList<>();
        for (String registry : registries) {
            Result<String, String> document = fetchText(registry.replaceAll("/+$", "") + "/index.json");
            if (document.isErr()) {
                log.debug("registry {} unavailable ({})", registry, document.errorAsOptional().orElse("?"));
                continue;
            }
            Map<String, Object> index;
            try {
                index = mapper.readValue(document.orElseThrow(), Map.class);
            } catch (RuntimeException e) {
                log.debug("registry {} served an index that is not JSON", registry);
                continue;
            }
            if (!(index.get("packages") instanceof List<?> packages)) {
                continue;
            }
            for (Object element : packages) {
                if (!(element instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> entry = (Map<String, Object>) raw;
                try {
                    found.add(new Listing(
                            String.valueOf(entry.get("name")),
                            String.valueOf(entry.get("version")),
                            String.valueOf(entry.getOrDefault("kind", "")),
                            String.valueOf(entry.getOrDefault("description", "")),
                            String.valueOf(entry.get("url")),
                            String.valueOf(entry.get("digest")),
                            Optional.ofNullable(entry.get("publisher")).map(Object::toString),
                            registry));
                } catch (RuntimeException e) {
                    // One malformed entry costs that entry.
                }
            }
        }
        found.sort(Comparator.comparing(Listing::name).thenComparing(Listing::version, ExtensionCatalog::newerFirst));
        return List.copyOf(found);
    }

    /** Listings whose name or description contains {@code query}, case-insensitively. */
    public List<Listing> search(String query) {
        String needle = Objects.requireNonNull(query, "query").toLowerCase(java.util.Locale.ROOT);
        return list().stream()
                .filter(listing -> listing.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || listing.description().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .toList();
    }

    /** The newest listing for a name, or the exact version when one is asked for. */
    public Optional<Listing> resolve(String name, Optional<String> version) {
        return list().stream()
                .filter(listing -> listing.name().equals(name))
                .filter(listing -> version.isEmpty() || listing.version().equals(version.get()))
                .findFirst();
    }

    /**
     * Downloads and unpacks a listing into a fresh directory under {@code into}, checking that
     * what arrived is what the index promised.
     *
     * @return the directory to hand to {@link ExtensionRegistry#install}
     */
    public Result<Path, String> fetch(Listing listing, Path into) {
        Objects.requireNonNull(listing, "listing");
        Objects.requireNonNull(into, "into");
        Result<byte[], String> archive = fetchBytes(listing.url());
        if (archive.isErr()) {
            return Result.err(archive.errorAsOptional().orElse("download_failed"));
        }
        Path target;
        try {
            target = Files.createDirectories(into.resolve(listing.name() + "-" + listing.version()));
        } catch (IOException e) {
            return Result.err("cannot_create_directory");
        }
        Result<Map<String, byte[]>, String> unpacked = unzip(archive.orElseThrow(), target);
        if (unpacked.isErr()) {
            return Result.err(unpacked.errorAsOptional().orElse("unpack_failed"));
        }
        // The digest covers everything but the signature, which is exactly what a publisher signs.
        Map<String, byte[]> signed = new TreeMap<>(unpacked.orElseThrow());
        signed.remove("jclaw-extension.sig");
        String actual = PackageDigest.of(signed);
        if (!listing.digest().isBlank() && !actual.equals(listing.digest())) {
            return Result.err("digest_mismatch");
        }
        return Result.ok(target);
    }

    /** Unpacks a zip, refusing paths that would escape the directory or archives that are too big. */
    private Result<Map<String, byte[]>, String> unzip(byte[] archive, Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            long total = 0;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    return Result.err("archive_has_too_many_entries");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                // The oldest archive trick there is: a path that climbs out of the directory.
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    return Result.err("archive_entry_escapes_directory");
                }
                byte[] bytes = zip.readAllBytes();
                total += bytes.length;
                if (total > MAX_ARCHIVE_BYTES) {
                    return Result.err("archive_too_large");
                }
                Files.createDirectories(out.getParent());
                Files.write(out, bytes);
                files.put(root.relativize(out).toString().replace('\\', '/'), bytes);
            }
        } catch (IOException | RuntimeException e) {
            return Result.err("archive_unreadable");
        }
        return files.isEmpty() ? Result.err("archive_empty") : Result.ok(files);
    }

    private Result<String, String> fetchText(String url) {
        return fetchBytes(url).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    private Result<byte[], String> fetchBytes(String url) {
        Result<URI, String> checked = egress.check(url);
        if (checked.isErr()) {
            return Result.err("endpoint_" + checked.errorAsOptional().orElse("denied"));
        }
        try (InputStream ignored = null) {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(checked.orElseThrow())
                            .timeout(Duration.ofSeconds(30))
                            .header("Accept", "application/json, application/zip")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                return Result.err("http_" + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_ARCHIVE_BYTES) {
                return Result.err("response_too_large");
            }
            return Result.ok(body);
        } catch (IOException e) {
            return Result.err("unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err("interrupted");
        }
    }

    /** An installed extension and the newer version a registry offers. */
    public record Upgrade(ExtensionRegistry.Installed installed, Listing listing) { }

    /**
     * Installed extensions a registry has something newer for.
     *
     * <p>Only the name is matched, and only a strictly newer version counts. An extension no
     * registry lists — installed from a directory, say — is simply not upgradable, which is not
     * the same as being current but is not different in any way the operator can act on.
     */
    public List<Upgrade> upgradesFor(List<ExtensionRegistry.Installed> installed) {
        List<Listing> offered = list();
        List<Upgrade> upgrades = new ArrayList<>();
        for (ExtensionRegistry.Installed extension : installed) {
            offered.stream()
                    .filter(listing -> listing.name().equals(extension.name()))
                    .filter(listing -> isNewer(listing.version(), extension.manifest().version()))
                    .findFirst()
                    .ifPresent(listing -> upgrades.add(new Upgrade(extension, listing)));
        }
        return List.copyOf(upgrades);
    }

    /** Orders version strings newest first, comparing numeric runs numerically. */
    static int newerFirst(String left, String right) {
        String[] a = left.split("[._-]");
        String[] b = right.split("[._-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            int compared;
            if (x.matches("\\d+") && y.matches("\\d+")) {
                compared = Long.compare(Long.parseLong(y), Long.parseLong(x));
            } else {
                compared = y.compareTo(x);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    /** Whether {@code candidate} is a newer version than {@code installed}. */
    public static boolean isNewer(String candidate, String installed) {
        return newerFirst(candidate, installed) < 0;
    }
}
