// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.skill;

import io.jclaw.contracts.skill.SkillCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads skills from disk: one directory per skill, each containing a {@code SKILL.md}.
 *
 * <p>Files rather than a database, deliberately. A skill is prose that a human writes, reviews,
 * versions, and diffs; keeping it as markdown on disk means git works on it and no tooling is
 * needed to read one. It also makes a skill trivially shareable — copy the directory.
 *
 * <p>{@code SKILL.md} carries YAML-style frontmatter for the metadata that goes into the prompt:
 *
 * <pre>
 * ---
 * name: Code review
 * description: House checklist for reviewing a change
 * when-to-use: reviewing a diff or a pull request
 * ---
 * The full instructions follow here...
 * </pre>
 *
 * <p>The parser is intentionally minimal — flat {@code key: value} pairs only — rather than pulling
 * in a YAML engine for four fields. A malformed skill is skipped rather than failing the process:
 * one bad skill directory must not stop the agent from starting.
 */
public final class FilesystemSkillCatalog implements SkillCatalog {

    private static final String SKILL_FILE = "SKILL.md";
    private static final String DELIMITER = "---";

    /** Cap on a single skill body, so one enormous file cannot swamp a tool result. */
    private static final int MAX_INSTRUCTIONS = 64 * 1024;

    private final Path root;

    public FilesystemSkillCatalog(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** The directory skills are read from. Created on demand so a fresh install has somewhere to put them. */
    public Path root() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create skills directory " + root, e);
        }
        return root;
    }

    @Override
    public List<Skill> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> directories = Files.list(root)) {
            List<Skill> skills = new ArrayList<>();
            directories.filter(Files::isDirectory)
                    .forEach(directory -> read(directory).ifPresent(skills::add));
            skills.sort(Comparator.comparing(Skill::id));
            return List.copyOf(skills);
        } catch (IOException e) {
            // An unreadable skills directory means no skills, not a dead agent.
            return List.of();
        }
    }

    @Override
    public Optional<Skill> find(String id) {
        Objects.requireNonNull(id, "id");
        // Resolve by listing rather than by path arithmetic: an id like "../../etc" must not
        // escape the skills directory just because it is used as a filename.
        return list().stream().filter(skill -> skill.id().equals(id)).findFirst();
    }

    /** Parses one skill directory, or empty when it has no readable SKILL.md. */
    private Optional<Skill> read(Path directory) {
        Path file = directory.resolve(SKILL_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Parsed parsed = parse(content);
            String id = directory.getFileName().toString();
            return Optional.of(new Skill(
                    id,
                    parsed.metadata().getOrDefault("name", id),
                    parsed.metadata().getOrDefault("description", ""),
                    parsed.metadata().getOrDefault("when-to-use", ""),
                    parsed.body().length() > MAX_INSTRUCTIONS
                            ? parsed.body().substring(0, MAX_INSTRUCTIONS) + "\n[truncated]"
                            : parsed.body()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private record Parsed(Map<String, String> metadata, String body) {
    }

    /** Splits frontmatter from body. A file without frontmatter is all body. */
    static Parsed parse(String content) {
        String normalized = content.stripLeading();
        if (!normalized.startsWith(DELIMITER)) {
            return new Parsed(Map.of(), content.strip());
        }
        int closing = normalized.indexOf("\n" + DELIMITER, DELIMITER.length());
        if (closing < 0) {
            return new Parsed(Map.of(), content.strip());
        }

        String frontmatter = normalized.substring(DELIMITER.length(), closing);
        String body = normalized.substring(closing + DELIMITER.length() + 1).strip();

        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                metadata.put(
                        line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return new Parsed(metadata, body);
    }
}
