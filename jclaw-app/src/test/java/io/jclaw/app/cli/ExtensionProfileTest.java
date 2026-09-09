package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import io.jclaw.contracts.extension.ExtensionRegistry;
import io.jclaw.storage.extension.FilesystemExtensionRegistry;
import io.jclaw.storage.jsonl.JsonlFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profiles: enabling one named set of extensions and, just as importantly, disabling the rest.
 *
 * <p>The configuration half binds through {@link Binder} rather than the record's canonical
 * constructor. That is not only convenience — it is the path Spring actually uses at startup, so
 * a property that binds here binds in the application, and a test written this way does not have
 * to be edited every time an unrelated setting is added.
 */
class ExtensionProfileTest {

    @TempDir Path dir;

    private FilesystemExtensionRegistry registry() {
        return new FilesystemExtensionRegistry(dir.resolve("extensions"),
                new JsonlFile(dir.resolve("extensions.jsonl")), Map.of(), Optional.empty(), Clock.systemUTC());
    }

    private Path skill(String name) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("pkg-" + name));
        Files.writeString(pkg.resolve("jclaw-extension.json"),
                "{\"name\":\"" + name + "\",\"version\":\"1.0\",\"kind\":\"skill\",\"description\":\"d\"}");
        Files.writeString(pkg.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: d\nwhen-to-use: w\n---\nBody.\n");
        return pkg;
    }

    @Test
    @DisplayName("a profile enables what it lists and disables what it omits")
    void appliesExactly() throws IOException {
        FilesystemExtensionRegistry extensions = registry();
        extensions.install(skill("reviewed"), Map.of()).orElseThrow();
        extensions.install(skill("scratch"), Map.of()).orElseThrow();
        assertTrue(extensions.setEnabled("reviewed", false));

        var applied = ExtensionsCommand.applyProfile(extensions,
                new LinkedHashSet<>(List.of("reviewed", "never-installed")));

        assertEquals(List.of("reviewed"), applied.enabled());
        assertEquals(List.of("scratch"), applied.disabled(),
                "a profile that could not take anything away would not be worth switching to");
        assertEquals(List.of("never-installed"), applied.missing());
        assertTrue(extensions.find("reviewed").orElseThrow().enabled());
        assertFalse(extensions.find("scratch").orElseThrow().enabled());

        // Applying the same profile again changes nothing: it is a state, not a step.
        var again = ExtensionsCommand.applyProfile(extensions,
                new LinkedHashSet<>(List.of("reviewed", "never-installed")));
        assertEquals(List.of(), again.enabled());
        assertEquals(List.of(), again.disabled());
    }

    @Test
    @DisplayName("an empty profile disables everything, which is a legitimate thing to ask for")
    void emptyProfileDisablesAll() throws IOException {
        FilesystemExtensionRegistry extensions = registry();
        extensions.install(skill("one"), Map.of()).orElseThrow();
        extensions.install(skill("two"), Map.of()).orElseThrow();

        var applied = ExtensionsCommand.applyProfile(extensions, Set.of());
        assertEquals(Set.of("one", "two"), Set.copyOf(applied.disabled()));
        assertTrue(extensions.list().stream().noneMatch(ExtensionRegistry.Installed::enabled));
    }

    @Test
    @DisplayName("the active profile's members come from configuration, and absence is not an error")
    void profileMembersFromConfiguration() {
        assertTrue(bound(Map.of()).profileMembers().isEmpty(), "no profile selected");

        JclawProperties selected = bound(Map.of(
                "jclaw.extension-profiles.prod", " reviewed , signed ,, ",
                "jclaw.extension-profile", "prod"));
        assertEquals(Optional.of(new LinkedHashSet<>(List.of("reviewed", "signed"))),
                selected.profileMembers(), "blank members are dropped, spacing is not significant");

        assertTrue(bound(Map.of(
                "jclaw.extension-profiles.prod", "reviewed",
                "jclaw.extension-profile", "staging")).profileMembers().isEmpty(),
                "selecting a profile that is not defined names nothing rather than failing");
    }

    @Test
    @DisplayName("registry URLs bind as a list, and blank entries are dropped")
    void registriesBind() {
        assertEquals(List.of(), bound(Map.of()).extensionRegistries());
        assertEquals(List.of("https://a.example/reg", "https://b.example/reg"),
                bound(Map.of("jclaw.extension-registries",
                        "https://a.example/reg,,https://b.example/reg")).extensionRegistries(),
                "a blank entry from a trailing comma is not a registry");
    }

    private JclawProperties bound(Map<String, String> settings) {
        Map<String, Object> source = new LinkedHashMap<>(settings);
        source.put("jclaw.workspace", dir.toString());
        source.put("jclaw.state-dir", dir.toString());
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("jclaw", JclawProperties.class)
                .orElseThrow(() -> new IllegalStateException("jclaw.* did not bind"));
    }
}
