// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.adapter.out.capability;

import io.jclaw.ports.Result;
import io.jclaw.ports.capability.CapabilityDescriptor;
import io.jclaw.ports.capability.CapabilityHandler;
import io.jclaw.ports.capability.CapabilityInvocation;
import io.jclaw.ports.capability.EffectClass;
import io.jclaw.ports.capability.HandlerError;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Workspace filesystem capabilities.
 *
 * <p>Every path argument goes through {@link CapabilityHandler.HandlerContext#resolvePath}, which
 * is the workspace guard. These handlers never call {@code Path.of} on model-supplied text
 * directly — that is the single discipline that keeps a prompt-injected {@code ../../.ssh/id_rsa}
 * from being read.
 *
 * <p>A guard rejection maps to {@link HandlerError#denied}, not to a failure. The audit log needs
 * to distinguish "the boundary stopped this" from "the code broke", or blocked attacks hide among
 * ordinary I/O errors.
 *
 * <p>Output is bounded here as well as by the kernel. A lane that reads a 2 GB file into memory
 * before the kernel truncates it has already lost, so the limit is applied while reading.
 */
public final class FileTools {

    /** Maximum bytes any single file operation will read. */
    private static final int MAX_READ_BYTES = 256 * 1024;

    /** Maximum entries a listing or search will return. */
    private static final int MAX_ENTRIES = 500;

    private FileTools() {
    }

    /** All filesystem handlers, ready to register. */
    public static List<CapabilityHandler> all() {
        return List.of(new ReadFile(), new WriteFile(), new ListDir(), new Glob(), new Grep());
    }

    /** Reads a UTF-8 text file from the workspace. */
    public static final class ReadFile implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "read_file",
                "Read a UTF-8 text file from the workspace. Returns the file's contents.",
                EffectClass.READ_LOCAL,
                Schemas.object(
                        Schemas.properties("path", Schemas.string("Workspace-relative path to read.")),
                        List.of("path")));

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            return resolve(context, invocation.stringArg("path", "")).flatMap(ReadFile::read);
        }

        private static Result<String, HandlerError> read(Path path) {
            if (!Files.isRegularFile(path)) {
                return Result.err(HandlerError.failed("not_a_file"));
            }
            try {
                if (Files.size(path) > MAX_READ_BYTES) {
                    return Result.err(HandlerError.failed("file_too_large"));
                }
                return Result.ok(Files.readString(path, StandardCharsets.UTF_8));
            } catch (MalformedInputException e) {
                return Result.err(HandlerError.failed("not_utf8_text"));
            } catch (IOException e) {
                return Result.err(HandlerError.failed("read_failed"));
            }
        }
    }

    /** Writes a UTF-8 text file inside the workspace, creating parent directories. */
    public static final class WriteFile implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "write_file",
                "Write UTF-8 text to a file in the workspace, replacing any existing content.",
                EffectClass.WRITE_LOCAL,
                Schemas.object(
                        Schemas.properties(
                                "path", Schemas.string("Workspace-relative path to write."),
                                "content", Schemas.string("Full file content to write.")),
                        List.of("path", "content")));

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String content = invocation.stringArg("content", "");
            return resolve(context, invocation.stringArg("path", ""))
                    .flatMap(path -> write(path, content, context));
        }

        private static Result<String, HandlerError> write(
                Path path, String content, HandlerContext context) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, content, StandardCharsets.UTF_8);
                return Result.ok("Wrote " + content.length() + " chars to " + context.displayPath(path));
            } catch (IOException e) {
                return Result.err(HandlerError.failed("write_failed"));
            }
        }
    }

    /** Lists a directory's immediate entries. */
    public static final class ListDir implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "list_dir",
                "List the immediate entries of a workspace directory.",
                EffectClass.READ_LOCAL,
                Schemas.object(
                        Schemas.properties("path", Schemas.string(
                                "Workspace-relative directory. Defaults to the root.")),
                        List.of()));

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            return resolve(context, invocation.stringArg("path", ".")).flatMap(ListDir::list);
        }

        private static Result<String, HandlerError> list(Path dir) {
            if (!Files.isDirectory(dir)) {
                return Result.err(HandlerError.failed("not_a_directory"));
            }
            try (Stream<Path> entries = Files.list(dir)) {
                String listing = entries
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(MAX_ENTRIES)
                        .map(path -> Files.isDirectory(path)
                                ? path.getFileName() + "/"
                                : path.getFileName().toString())
                        .collect(Collectors.joining("\n"));
                return Result.ok(listing.isEmpty() ? "(empty directory)" : listing);
            } catch (IOException e) {
                return Result.err(HandlerError.failed("list_failed"));
            }
        }
    }

    /** Finds files by glob pattern. */
    public static final class Glob implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "glob",
                "Find files in the workspace matching a glob pattern, e.g. '**/*.java'.",
                EffectClass.READ_LOCAL,
                Schemas.object(
                        Schemas.properties("pattern", Schemas.string(
                                "Glob pattern, relative to the workspace root.")),
                        List.of("pattern")));

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            String pattern = invocation.stringArg("pattern", "");
            if (pattern.isBlank()) {
                return Result.err(HandlerError.failed("pattern_required"));
            }
            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            } catch (IllegalArgumentException e) {
                return Result.err(HandlerError.failed("pattern_invalid"));
            }
            return resolve(context, ".").flatMap(root -> walk(root, matcher, context));
        }

        private static Result<String, HandlerError> walk(
                Path root, PathMatcher matcher, HandlerContext context) {
            try (Stream<Path> walk = Files.walk(root)) {
                List<String> matches = walk
                        .filter(Files::isRegularFile)
                        .filter(path -> matcher.matches(root.relativize(path)))
                        .limit(MAX_ENTRIES)
                        .map(context::displayPath)
                        .sorted()
                        .toList();
                return Result.ok(matches.isEmpty() ? "(no matches)" : String.join("\n", matches));
            } catch (IOException e) {
                return Result.err(HandlerError.failed("walk_failed"));
            }
        }
    }

    /** Searches file contents by regular expression. */
    public static final class Grep implements CapabilityHandler {

        private static final CapabilityDescriptor DESCRIPTOR = CapabilityDescriptor.builtin(
                "grep",
                "Search workspace file contents with a regular expression. "
                        + "Returns matching lines prefixed with their file and line number.",
                EffectClass.READ_LOCAL,
                Schemas.object(
                        Schemas.properties(
                                "pattern", Schemas.string("Java regular expression to search for."),
                                "glob", Schemas.string("Optional glob limiting which files are searched.")),
                        List.of("pattern")));

        @Override
        public CapabilityDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Result<String, HandlerError> execute(CapabilityInvocation invocation, HandlerContext context) {
            Pattern regex;
            try {
                regex = Pattern.compile(invocation.stringArg("pattern", ""));
            } catch (PatternSyntaxException e) {
                return Result.err(HandlerError.failed("pattern_invalid"));
            }
            PathMatcher matcher;
            try {
                matcher = FileSystems.getDefault()
                        .getPathMatcher("glob:" + invocation.stringArg("glob", "**"));
            } catch (IllegalArgumentException e) {
                return Result.err(HandlerError.failed("glob_invalid"));
            }
            return resolve(context, ".").flatMap(root -> search(root, regex, matcher, context));
        }

        private static Result<String, HandlerError> search(
                Path root, Pattern regex, PathMatcher matcher, HandlerContext context) {

            List<String> hits = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    if (hits.size() >= MAX_ENTRIES) {
                        break;
                    }
                    if (matcher.matches(root.relativize(path))) {
                        collectMatches(path, regex, context, hits);
                    }
                }
            } catch (IOException e) {
                return Result.err(HandlerError.failed("walk_failed"));
            }
            return Result.ok(hits.isEmpty() ? "(no matches)" : String.join("\n", hits));
        }

        /** Reads one file and appends its matching lines. Binary and oversized files are skipped. */
        private static void collectMatches(
                Path path, Pattern regex, HandlerContext context, List<String> hits) {
            try {
                if (Files.size(path) > MAX_READ_BYTES) {
                    return;
                }
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size() && hits.size() < MAX_ENTRIES; i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        hits.add(context.displayPath(path) + ":" + (i + 1) + ": " + lines.get(i).strip());
                    }
                }
            } catch (IOException e) {
                // Unreadable or non-UTF-8 file: skipped, exactly as a grep would.
            }
        }
    }

    /**
     * Resolves a caller-supplied path through the workspace guard, converting a rejection into a
     * {@link HandlerError.Denied}. Every path in this file goes through here.
     */
    private static Result<Path, HandlerError> resolve(
            CapabilityHandler.HandlerContext context, String candidate) {
        return context.resolvePath(candidate).mapErr(HandlerError::denied);
    }
}
