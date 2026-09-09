package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.contracts.extension.ExtensionRegistry.Installed;
import io.jclaw.domain.extension.ExtensionSignature;
import io.jclaw.storage.extension.FilesystemExtensionRegistry;
import io.jclaw.storage.extension.PublisherKeys;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Installs, lists, and signs extension packages.
 *
 * <p>Installing prints what the manifest declares and the trust the package earned, so the
 * operator sees a {@code COMMUNITY} install for what it is: third-party code whose every tool
 * call will gate. {@code keygen} and {@code sign} are the publisher's side; a publisher signs the
 * package digest, and an operator who lists the publisher's key under
 * {@code jclaw.trusted-publishers} gets {@code VERIFIED} installs whose declared effect class
 * is believed.
 *
 * <p>{@code search}, {@code add}, {@code outdated}, and {@code upgrade} work against the
 * registries named in {@code jclaw.extension-registries}. Coming from a registry earns a package
 * nothing: what is downloaded is checked against the digest the index advertised, then installed
 * down the same path a local directory takes, where the signature decides trust. A registry is a
 * convenient way to find a package, not a reason to believe it.
 */
@Component
@Command(
        name = "extensions",
        description = "Install and manage extension packages, locally or from a registry.",
        mixinStandardHelpOptions = true,
        subcommands = {
                ExtensionsCommand.Install.class, ExtensionsCommand.ListAll.class, ExtensionsCommand.Remove.class,
                ExtensionsCommand.Enable.class, ExtensionsCommand.Disable.class,
                ExtensionsCommand.Keygen.class, ExtensionsCommand.Sign.class,
                ExtensionsCommand.Search.class, ExtensionsCommand.Add.class,
                ExtensionsCommand.Outdated.class, ExtensionsCommand.Upgrade.class,
                ExtensionsCommand.Profile.class})
public class ExtensionsCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    static void describe(Installed installed) {
        var m = installed.manifest();
        System.out.printf("%-20s %-8s %-6s %-9s %s%n", m.name(), m.version(),
                m.kind().name().toLowerCase(java.util.Locale.ROOT), installed.trust(),
                installed.enabled() ? "enabled" : "disabled");
        if (!m.description().isBlank()) {
            System.out.println("    " + m.description());
        }
        if (m.kind() == ExtensionRegistry.Kind.MCP) {
            System.out.println("    command: " + String.join(" ", m.command())
                    + "   tools: " + installed.effectiveEffect() + "/" + installed.trust());
        }
        if (!m.env().isEmpty()) {
            System.out.println("    env: " + m.env().stream()
                    .map(name -> name + " <- secret " + installed.secrets().getOrDefault(name, "(unset)"))
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (!m.hosts().isEmpty()) {
            System.out.println("    declares reaching: " + String.join(", ", m.hosts()));
        }
        m.publisher().ifPresent(p -> System.out.println("    publisher: " + p));
    }


    static void describeListing(io.jclaw.app.extension.ExtensionCatalog.Listing listing) {
        System.out.printf("%-20s %-8s %-6s %s%n", listing.name(), listing.version(),
                listing.kind(), listing.registry());
        if (!listing.description().isBlank()) {
            System.out.println("    " + listing.description());
        }
        listing.publisher().ifPresent(publisher -> System.out.println("    publisher: " + publisher));
    }

    /** What applying a profile did: what it turned on, what it turned off, what it could not find. */
    record Applied(List<String> enabled, List<String> disabled, List<String> missing) { }

    /**
     * Enables exactly the extensions a profile names.
     *
     * <p>A profile says "exactly these", so what it omits is turned off rather than left as it
     * was. A profile that could only ever enable things would be unable to take anything away,
     * which is most of what an operator wants a profile for: switching from the permissive set to
     * the reviewed one has to actually remove the permissive extras.
     *
     * <p>A name the profile lists but nothing has installed is reported rather than treated as an
     * error. The profile is a statement of intent that may run ahead of what is on this machine.
     */
    static Applied applyProfile(ExtensionRegistry registry, java.util.Set<String> wanted) {
        List<String> enabled = new java.util.ArrayList<>();
        List<String> disabled = new java.util.ArrayList<>();
        var missing = new java.util.LinkedHashSet<>(wanted);
        for (Installed installed : registry.list()) {
            missing.remove(installed.name());
            boolean shouldRun = wanted.contains(installed.name());
            if (installed.enabled() != shouldRun && registry.setEnabled(installed.name(), shouldRun)) {
                (shouldRun ? enabled : disabled).add(installed.name());
            }
        }
        return new Applied(List.copyOf(enabled), List.copyOf(disabled), List.copyOf(missing));
    }

    static Map<String, String> parseSecrets(String[] pairs) {
        Map<String, String> named = new LinkedHashMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                System.err.println("jclaw: --secret expects NAME=secret-name, got '" + pair + "'");
                return null;
            }
            named.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return named;
    }

    /**
     * Downloads a listing into a scratch directory and installs it from there.
     *
     * <p>The download lands under the state directory rather than the system temporary one, so a
     * half-fetched package is somewhere the operator already knows to look, and it is removed
     * whether or not the install succeeded — a package that failed to verify should not be left
     * lying about where someone could install it by hand.
     */
    static int installFrom(
            ExtensionRegistry registry, io.jclaw.app.extension.ExtensionCatalog catalog,
            JclawProperties properties, io.jclaw.app.extension.ExtensionCatalog.Listing listing,
            Map<String, String> secrets) {
        Path scratch;
        try {
            scratch = Files.createTempDirectory(
                    Files.createDirectories(properties.extensionsPath().resolve("downloads")), "fetch-");
        } catch (IOException e) {
            System.err.println("jclaw: cannot write a download directory (" + e.getMessage() + ")");
            return 1;
        }
        try {
            var fetched = catalog.fetch(listing, scratch);
            if (fetched.isErr()) {
                System.err.println("jclaw: " + listing.coordinate() + " not installed ("
                        + fetched.errorAsOptional().orElse("fetch_failed") + ")");
                return 1;
            }
            return registry.install(fetched.orElseThrow(), secrets).fold(
                    installed -> {
                        System.out.println("installed from " + listing.registry() + ":");
                        describe(installed);
                        if (installed.trust() != io.jclaw.contracts.capability.TrustClass.VERIFIED) {
                            System.out.println("    unsigned or unverified: COMMUNITY trust; every tool call will gate");
                        }
                        return 0;
                    },
                    reason -> {
                        System.err.println("jclaw: " + listing.coordinate() + " not installed (" + reason + ")");
                        return 1;
                    });
        } finally {
            deleteTree(scratch);
        }
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover scratch file is untidy, never unsafe.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    @Component
    @Command(name = "install", description = "Install a package directory.", mixinStandardHelpOptions = true)
    public static class Install implements Callable<Integer> {

        private final ExtensionRegistry registry;

        @Parameters(index = "0", description = "Directory holding jclaw-extension.json.")
        private Path packageDir;

        @Option(names = "--secret",
                description = "NAME=secret-name: the vault secret supplying a variable the manifest "
                        + "requires. Names, not values. Bind each to the capability mcp.connect. Repeatable.")
        private String[] secrets = new String[0];

        public Install(ExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            Map<String, String> named = parseSecrets(secrets);
            if (named == null) {
                return 1;
            }
            return registry.install(packageDir, named).fold(
                    installed -> {
                        System.out.println("installed:");
                        describe(installed);
                        if (installed.trust() != io.jclaw.contracts.capability.TrustClass.VERIFIED) {
                            System.out.println("    unsigned or unverified: COMMUNITY trust; every tool call will gate");
                        }
                        return 0;
                    },
                    reason -> {
                        System.err.println("jclaw: not installed (" + reason + ")");
                        return 1;
                    });
        }
    }

    @Component
    @Command(name = "list", description = "List installed extensions.", mixinStandardHelpOptions = true)
    public static class ListAll implements Callable<Integer> {

        private final ExtensionRegistry registry;

        public ListAll(ExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            var installed = registry.list();
            if (installed.isEmpty()) {
                System.out.println("(no extensions installed)");
                return 0;
            }
            installed.forEach(ExtensionsCommand::describe);
            return 0;
        }
    }

    @Component
    @Command(name = "remove", description = "Uninstall an extension.", mixinStandardHelpOptions = true)
    public static class Remove implements Callable<Integer> {

        private final ExtensionRegistry registry;

        @Parameters(index = "0", description = "Extension name.")
        private String name;

        public Remove(ExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            if (registry.remove(name)) {
                System.out.println("removed " + name);
                return 0;
            }
            System.err.println("jclaw: no such extension: " + name);
            return 1;
        }
    }

    @Component
    @Command(name = "enable", description = "Enable an extension.", mixinStandardHelpOptions = true)
    public static class Enable implements Callable<Integer> {

        private final ExtensionRegistry registry;

        @Parameters(index = "0", description = "Extension name.")
        private String name;

        public Enable(ExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            return toggle(registry, name, true);
        }
    }

    @Component
    @Command(name = "disable", description = "Disable an extension without removing it.", mixinStandardHelpOptions = true)
    public static class Disable implements Callable<Integer> {

        private final ExtensionRegistry registry;

        @Parameters(index = "0", description = "Extension name.")
        private String name;

        public Disable(ExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() {
            return toggle(registry, name, false);
        }
    }

    private static int toggle(ExtensionRegistry registry, String name, boolean enabled) {
        if (registry.setEnabled(name, enabled)) {
            System.out.println((enabled ? "enabled " : "disabled ") + name);
            return 0;
        }
        System.err.println("jclaw: no such extension: " + name);
        return 1;
    }

    @Component
    @Command(name = "keygen", description = "Generate a publisher key pair for signing packages.",
            mixinStandardHelpOptions = true)
    public static class Keygen implements Callable<Integer> {

        @Option(names = "--out", required = true,
                description = "Directory to write publisher.key (private, owner-only) and publisher.pub.")
        private Path out;

        @Override
        public Integer call() throws IOException {
            PublisherKeys.Pair pair = PublisherKeys.generate();
            Files.createDirectories(out);
            Path priv = out.resolve("publisher.key");
            Files.writeString(priv, pair.privateKey() + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            Files.writeString(out.resolve("publisher.pub"), pair.publicKey() + System.lineSeparator(), StandardCharsets.UTF_8);
            System.out.println("wrote " + priv + " (keep private) and " + out.resolve("publisher.pub"));
            System.out.println("operators trust it with: jclaw.trusted-publishers.<name>=" + pair.publicKey());
            return 0;
        }
    }

    @Component
    @Command(name = "sign", description = "Sign a package directory with a publisher key.",
            mixinStandardHelpOptions = true)
    public static class Sign implements Callable<Integer> {

        private final FilesystemExtensionRegistry registry;

        @Parameters(index = "0", description = "Directory holding jclaw-extension.json.")
        private Path packageDir;

        @Option(names = "--key", required = true, description = "The publisher.key file from keygen.")
        private Path key;

        public Sign(FilesystemExtensionRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Integer call() throws IOException {
            String privateKey = Files.readString(key, StandardCharsets.UTF_8).trim();
            return registry.digestOf(packageDir).fold(
                    digest -> {
                        try {
                            Files.writeString(packageDir.resolve(FilesystemExtensionRegistry.SIGNATURE_FILE),
                                    ExtensionSignature.sign(digest, privateKey) + System.lineSeparator());
                        } catch (IOException e) {
                            System.err.println("jclaw: cannot write signature (" + e.getMessage() + ")");
                            return 1;
                        }
                        System.out.println("signed " + packageDir + " (digest " + digest.substring(0, 12) + "…)");
                        return 0;
                    },
                    reason -> {
                        System.err.println("jclaw: cannot sign (" + reason + ")");
                        return 1;
                    });
        }
    }

    @Component
    @Command(name = "search", description = "Search the configured registries.", mixinStandardHelpOptions = true)
    public static class Search implements Callable<Integer> {

        private final io.jclaw.app.extension.ExtensionCatalog catalog;

        @Parameters(index = "0", arity = "0..1", description = "Text to look for. Omit to list everything.")
        private String query;

        public Search(io.jclaw.app.extension.ExtensionCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public Integer call() {
            if (!catalog.configured()) {
                System.err.println("jclaw: no registries configured (set jclaw.extension-registries)");
                return 1;
            }
            var found = query == null || query.isBlank() ? catalog.list() : catalog.search(query);
            if (found.isEmpty()) {
                System.out.println("(nothing matched in " + catalog.registries().size() + " registr"
                        + (catalog.registries().size() == 1 ? "y)" : "ies)"));
                return 0;
            }
            found.forEach(ExtensionsCommand::describeListing);
            return 0;
        }
    }

    @Component
    @Command(name = "add", description = "Install a package from a registry.", mixinStandardHelpOptions = true)
    public static class Add implements Callable<Integer> {

        private final ExtensionRegistry registry;
        private final io.jclaw.app.extension.ExtensionCatalog catalog;
        private final JclawProperties properties;

        @Parameters(index = "0", description = "Package name, optionally name@version.")
        private String coordinate;

        @Option(names = "--secret",
                description = "NAME=secret-name, as for install. Repeatable.")
        private String[] secrets = new String[0];

        public Add(ExtensionRegistry registry, io.jclaw.app.extension.ExtensionCatalog catalog,
                JclawProperties properties) {
            this.registry = registry;
            this.catalog = catalog;
            this.properties = properties;
        }

        @Override
        public Integer call() {
            if (!catalog.configured()) {
                System.err.println("jclaw: no registries configured (set jclaw.extension-registries)");
                return 1;
            }
            Map<String, String> named = parseSecrets(secrets);
            if (named == null) {
                return 1;
            }
            int at = coordinate.indexOf('@');
            String name = at < 0 ? coordinate : coordinate.substring(0, at);
            var version = at < 0 ? java.util.Optional.<String>empty()
                    : java.util.Optional.of(coordinate.substring(at + 1));
            var listing = catalog.resolve(name, version);
            if (listing.isEmpty()) {
                System.err.println("jclaw: no registry offers " + coordinate);
                return 1;
            }
            return installFrom(registry, catalog, properties, listing.get(), named);
        }
    }

    @Command(name = "outdated", description = "Installed extensions a registry offers a newer version of.",
            mixinStandardHelpOptions = true)
    @Component
    public static class Outdated implements Callable<Integer> {

        private final ExtensionRegistry registry;
        private final io.jclaw.app.extension.ExtensionCatalog catalog;

        public Outdated(ExtensionRegistry registry, io.jclaw.app.extension.ExtensionCatalog catalog) {
            this.registry = registry;
            this.catalog = catalog;
        }

        @Override
        public Integer call() {
            if (!catalog.configured()) {
                System.err.println("jclaw: no registries configured (set jclaw.extension-registries)");
                return 1;
            }
            int count = 0;
            for (var upgrade : catalog.upgradesFor(registry.list())) {
                System.out.printf("%-20s %s -> %s   (%s)%n", upgrade.installed().name(),
                        upgrade.installed().manifest().version(), upgrade.listing().version(),
                        upgrade.listing().registry());
                count++;
            }
            if (count == 0) {
                System.out.println("(everything installed is current)");
            }
            return 0;
        }
    }

    @Component
    @Command(name = "upgrade", description = "Upgrade installed extensions from a registry.",
            mixinStandardHelpOptions = true)
    public static class Upgrade implements Callable<Integer> {

        private final ExtensionRegistry registry;
        private final io.jclaw.app.extension.ExtensionCatalog catalog;
        private final JclawProperties properties;

        @Parameters(index = "0", arity = "0..1", description = "Extension to upgrade. Omit for all of them.")
        private String name;

        @Option(names = "--dry-run", description = "Report what would be upgraded and change nothing.")
        private boolean dryRun;

        public Upgrade(ExtensionRegistry registry, io.jclaw.app.extension.ExtensionCatalog catalog,
                JclawProperties properties) {
            this.registry = registry;
            this.catalog = catalog;
            this.properties = properties;
        }

        @Override
        public Integer call() {
            if (!catalog.configured()) {
                System.err.println("jclaw: no registries configured (set jclaw.extension-registries)");
                return 1;
            }
            int failures = 0;
            int done = 0;
            for (var upgrade : catalog.upgradesFor(registry.list())) {
                if (name != null && !name.equals(upgrade.installed().name())) {
                    continue;
                }
                done++;
                if (dryRun) {
                    System.out.println("would upgrade " + upgrade.installed().name() + " "
                            + upgrade.installed().manifest().version() + " -> " + upgrade.listing().version());
                    continue;
                }
                // The secrets an install was given are the operator's, not the registry's: a
                // package that names the same variables keeps them across the upgrade.
                if (installFrom(registry, catalog, properties, upgrade.listing(),
                        upgrade.installed().secrets()) != 0) {
                    failures++;
                }
            }
            if (done == 0) {
                System.out.println(name == null ? "(everything installed is current)"
                        : "(" + name + " is current or not installed)");
            }
            return failures == 0 ? 0 : 1;
        }
    }

    @Component
    @Command(name = "profile", description = "Enable exactly the extensions a named profile lists.",
            mixinStandardHelpOptions = true)
    public static class Profile implements Callable<Integer> {

        private final ExtensionRegistry registry;
        private final JclawProperties properties;

        @Parameters(index = "0", arity = "0..1",
                description = "Profile to apply. Omit to show the profiles and the active one.")
        private String name;

        public Profile(ExtensionRegistry registry, JclawProperties properties) {
            this.registry = registry;
            this.properties = properties;
        }

        @Override
        public Integer call() {
            if (name == null) {
                if (properties.extensionProfiles().isEmpty()) {
                    System.out.println("(no profiles configured; set jclaw.extension-profiles.<name>)");
                    return 0;
                }
                properties.extensionProfiles().forEach((profile, members) ->
                        System.out.printf("%-14s %s%s%n", profile, members,
                                profile.equals(properties.extensionProfile()) ? "   (active)" : ""));
                return 0;
            }
            String members = properties.extensionProfiles().get(name);
            if (members == null) {
                System.err.println("jclaw: no profile named '" + name + "'");
                return 1;
            }
            var wanted = java.util.Arrays.stream(members.split(","))
                    .map(String::trim).filter(member -> !member.isEmpty())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            Applied applied = applyProfile(registry, wanted);
            applied.enabled().forEach(extension -> System.out.println("enabled  " + extension));
            applied.disabled().forEach(extension -> System.out.println("disabled " + extension));
            applied.missing().forEach(absent -> System.out.println("not installed: " + absent));
            System.out.println("profile '" + name + "': "
                    + (applied.enabled().size() + applied.disabled().size()) + " change(s)");
            return 0;
        }
    }
}
