// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.persistence.extension;

import io.jclaw.ports.Result;
import io.jclaw.ports.capability.EffectClass;
import io.jclaw.ports.capability.TrustClass;
import io.jclaw.ports.extension.ExtensionRegistry;
import io.jclaw.domain.extension.ExtensionSignature;
import io.jclaw.domain.extension.ManifestParser;
import io.jclaw.domain.extension.PackageDigest;
import io.jclaw.domain.wasm.WasmSpec;
import io.jclaw.adapter.out.persistence.rows.RowStore;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Extensions installed under a directory, with their metadata in a row store.
 *
 * <p>Installing copies the package into {@code <root>/<name>/} and records what was verified
 * about it. A skill package is also copied into the skills directory, so the skill catalog sees
 * it like any other skill; disabling removes that copy and enabling restores it from the
 * package. An MCP package is started by the MCP registry from the recorded command and
 * environment.
 *
 * <p>Trust is decided here, once, at install: a signature by a publisher the operator trusts
 * makes the installation {@code VERIFIED}; no signature makes it {@code COMMUNITY}; a signature
 * that does not verify refuses the install, since a package claiming a publisher it cannot
 * prove is worse than one claiming none.
 */
public final class FilesystemExtensionRegistry implements ExtensionRegistry {

    public static final String MANIFEST_FILE = "jclaw-extension.json";
    public static final String SIGNATURE_FILE = "jclaw-extension.sig";
    private static final String SKILL_FILE = "SKILL.md";
    /** The module a WASM package ships, at a fixed name so nothing has to be configured. */
    public static final String MODULE_FILE = "module.wasm";
    private static final String KIND_INSTALLED = "installed";
    private static final String KIND_REMOVED = "removed";
    private static final String KIND_ENABLED = "enabled";

    private final Path root;
    private final RowStore rows;
    private final Map<String, String> trustedPublishers;
    private final Optional<Path> skillsRoot;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * @param trustedPublishers publisher name to base64 X.509 Ed25519 public key
     * @param skillsRoot        where skill packages are made visible to the catalog
     */
    public FilesystemExtensionRegistry(
            Path root, RowStore rows, Map<String, String> trustedPublishers, Optional<Path> skillsRoot, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.rows = Objects.requireNonNull(rows, "rows");
        this.trustedPublishers = Map.copyOf(Objects.requireNonNull(trustedPublishers, "trustedPublishers"));
        this.skillsRoot = Objects.requireNonNull(skillsRoot, "skillsRoot");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path root() {
        return root;
    }

    /** Reads a package's manifest and computes its digest without installing it. */
    public Result<Manifest, String> inspect(Path packageDir) {
        return readPackage(packageDir).map(Package::manifest);
    }

    /** The digest of a package as a publisher would sign it. */
    public Result<String, String> digestOf(Path packageDir) {
        return readPackage(packageDir).map(Package::digest);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Result<Installed, String> install(Path packageDir, Map<String, String> secrets) {
        Objects.requireNonNull(packageDir, "packageDir");
        Objects.requireNonNull(secrets, "secrets");
        Result<Package, String> read = readPackage(packageDir);
        if (read.isErr()) {
            return Result.err(read.errorAsOptional().orElseThrow());
        }
        Package pkg = read.orElseThrow();
        Manifest manifest = pkg.manifest();

        TrustClass trust = TrustClass.COMMUNITY;
        if (pkg.signature().isPresent()) {
            String publisher = manifest.publisher().orElse("");
            String key = trustedPublishers.get(publisher);
            if (key == null) {
                return Result.err("publisher_not_trusted: " + (publisher.isEmpty() ? "(none named)" : publisher));
            }
            if (!ExtensionSignature.verify(pkg.digest(), pkg.signature().get(), key)) {
                return Result.err("signature_invalid");
            }
            trust = TrustClass.VERIFIED;
        }
        for (String required : manifest.env()) {
            if (secrets.get(required) == null || secrets.get(required).isBlank()) {
                return Result.err("secret_required_for: " + required);
            }
        }
        if (manifest.kind() == Kind.SKILL && !pkg.files().containsKey(SKILL_FILE)) {
            return Result.err("skill_package_needs_SKILL.md");
        }
        if (manifest.kind() == Kind.WASM && !pkg.files().containsKey(MODULE_FILE)) {
            return Result.err("wasm_package_needs_module.wasm");
        }
        if (manifest.kind() == Kind.WASM) {
            try {
                // Building the spec is the validation — WasmSpec's constructor is what rejects a
                // permission the host does not implement, and parsePermissions only normalises.
                // The result is discarded on purpose: the spec a call actually runs under is
                // built later from the operator's configuration, not from the manifest.
                WasmSpec.defaults().withPermissions(WasmSpec.parsePermissions(manifest.permissions()));
            } catch (IllegalArgumentException e) {
                return Result.err("wasm_permission_unknown");
            }
        }

        Path target = root.resolve(manifest.name());
        try {
            deleteTree(target);
            Files.createDirectories(target);
            for (Map.Entry<String, byte[]> file : pkg.files().entrySet()) {
                Path out = target.resolve(file.getKey());
                Files.createDirectories(out.getParent());
                Files.write(out, file.getValue());
            }
            pkg.signature().ifPresent(sig -> {
                try {
                    Files.writeString(target.resolve(SIGNATURE_FILE), sig);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot install extension into " + target, e);
        }

        Installed installed = new Installed(manifest, trust, pkg.digest(), secrets, true, clock.instant());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_INSTALLED);
        row.put("name", manifest.name());
        row.put("version", manifest.version());
        row.put("description", manifest.description());
        row.put("extensionKind", manifest.kind().name());
        row.put("command", manifest.command());
        row.put("envNames", manifest.env());
        row.put("hosts", manifest.hosts());
        row.put("permissions", manifest.permissions());
        row.put("tools", manifest.tools());
        row.put("effect", manifest.effect().name());
        manifest.publisher().ifPresent(p -> row.put("publisher", p));
        row.put("trust", trust.name());
        row.put("digest", pkg.digest());
        row.put("secrets", secrets);
        row.put("installedAt", installed.installedAt().toString());
        rows.append(row);
        exposeSkill(installed, true);
        return Result.ok(installed);
    }

    @Override
    public synchronized List<Installed> list() {
        return replay().values().stream()
                .sorted(Comparator.comparing(Installed::name))
                .toList();
    }

    @Override
    public synchronized Optional<Installed> find(String name) {
        return Optional.ofNullable(replay().get(name));
    }

    @Override
    public synchronized boolean remove(String name) {
        Installed installed = replay().get(name);
        if (installed == null) {
            return false;
        }
        exposeSkill(installed, false);
        try {
            deleteTree(root.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_REMOVED);
        row.put("name", name);
        row.put("at", clock.instant().toString());
        rows.append(row);
        return true;
    }

    @Override
    public synchronized boolean setEnabled(String name, boolean enabled) {
        Installed installed = replay().get(name);
        if (installed == null) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", KIND_ENABLED);
        row.put("name", name);
        row.put("enabled", enabled);
        row.put("at", clock.instant().toString());
        rows.append(row);
        exposeSkill(installed, enabled);
        return true;
    }

    /** A skill package is visible to the catalog exactly while it is enabled. */
    private void exposeSkill(Installed installed, boolean visible) {
        if (installed.manifest().kind() != Kind.SKILL || skillsRoot.isEmpty()) {
            return;
        }
        Path skillDir = skillsRoot.get().resolve(installed.name());
        try {
            deleteTree(skillDir);
            if (visible) {
                Files.createDirectories(skillDir);
                Files.copy(root.resolve(installed.name()).resolve(SKILL_FILE), skillDir.resolve(SKILL_FILE),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot expose skill " + installed.name(), e);
        }
    }

    private record Package(Manifest manifest, Map<String, byte[]> files, String digest, Optional<String> signature) {
    }

    @SuppressWarnings("unchecked")
    private Result<Package, String> readPackage(Path packageDir) {
        Objects.requireNonNull(packageDir, "packageDir");
        if (!Files.isDirectory(packageDir)) {
            return Result.err("package_not_a_directory");
        }
        Path manifestFile = packageDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return Result.err("manifest_missing");
        }
        Map<String, Object> raw;
        try {
            raw = mapper.readValue(Files.readString(manifestFile, StandardCharsets.UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            return Result.err("manifest_unreadable");
        }
        Result<Manifest, String> manifest = ManifestParser.parse(raw);
        if (manifest.isErr()) {
            return Result.err(manifest.errorAsOptional().orElseThrow());
        }
        Map<String, byte[]> files = new TreeMap<>();
        Optional<String> signature = Optional.empty();
        try (Stream<Path> walk = Files.walk(packageDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = packageDir.relativize(file).toString().replace('\\', '/');
                if (relative.equals(SIGNATURE_FILE)) {
                    signature = Optional.of(Files.readString(file, StandardCharsets.UTF_8).trim());
                    continue;
                }
                files.put(relative, Files.readAllBytes(file));
            }
        } catch (IOException e) {
            return Result.err("package_unreadable");
        }
        return Result.ok(new Package(manifest.orElseThrow(), files, PackageDigest.of(files), signature));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Installed> replay() {
        Map<String, Installed> live = new LinkedHashMap<>();
        for (Map<String, Object> row : rows.readAll()) {
            String name = String.valueOf(row.get("name"));
            switch (String.valueOf(row.get("kind"))) {
                case KIND_INSTALLED -> {
                    try {
                        Manifest manifest = new Manifest(
                                name,
                                String.valueOf(row.get("version")),
                                String.valueOf(row.getOrDefault("description", "")),
                                Kind.valueOf(String.valueOf(row.get("extensionKind"))),
                                strings(row.get("command")), strings(row.get("envNames")), strings(row.get("hosts")),
                                strings(row.get("permissions")), strings(row.get("tools")),
                                EffectClass.valueOf(String.valueOf(row.get("effect"))),
                                Optional.ofNullable(row.get("publisher")).map(Object::toString));
                        Map<String, String> secrets = new LinkedHashMap<>();
                        if (row.get("secrets") instanceof Map<?, ?> given) {
                            given.forEach((k, v) -> secrets.put(String.valueOf(k), String.valueOf(v)));
                        }
                        live.put(name, new Installed(manifest, TrustClass.valueOf(String.valueOf(row.get("trust"))),
                                String.valueOf(row.get("digest")), secrets, true,
                                Instant.parse(String.valueOf(row.get("installedAt")))));
                    } catch (RuntimeException e) {
                        // A damaged row is skipped, as everywhere else.
                    }
                }
                case KIND_REMOVED -> live.remove(name);
                case KIND_ENABLED -> {
                    Installed current = live.get(name);
                    if (current != null) {
                        live.put(name, new Installed(current.manifest(), current.trust(), current.digest(),
                                current.secrets(), Boolean.TRUE.equals(row.get("enabled")), current.installedAt()));
                    }
                }
                default -> { }
            }
        }
        return live;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> out.add(String.valueOf(item)));
        }
        return out;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
