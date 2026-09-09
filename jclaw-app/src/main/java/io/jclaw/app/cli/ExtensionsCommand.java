package io.jclaw.app.cli;

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
 */
@Component
@Command(
        name = "extensions",
        description = "Install and manage extension packages (skills and MCP servers).",
        mixinStandardHelpOptions = true,
        subcommands = {
                ExtensionsCommand.Install.class, ExtensionsCommand.ListAll.class, ExtensionsCommand.Remove.class,
                ExtensionsCommand.Enable.class, ExtensionsCommand.Disable.class,
                ExtensionsCommand.Keygen.class, ExtensionsCommand.Sign.class})
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
            Map<String, String> named = new LinkedHashMap<>();
            for (String pair : secrets) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    System.err.println("jclaw: --secret expects NAME=secret-name, got '" + pair + "'");
                    return 1;
                }
                named.put(pair.substring(0, eq), pair.substring(eq + 1));
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
}
