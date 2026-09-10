// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.kernel.guard;

import io.jclaw.contracts.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Confines filesystem access to a workspace root.
 *
 * <p>The containment check is deliberately done on <em>resolved</em> paths, not on the text of the
 * request. String-level checks are defeated by {@code ../}, by symlinks, by {@code //}, and by
 * absolute paths that merely start with the right prefix — {@code /work/../etc/passwd} passes a
 * naive {@code startsWith("/work")} test, and {@code /workspace-evil} passes it too.
 *
 * <p>So every path is normalized and, where it exists, resolved through symlinks before comparison.
 * A symlink inside the workspace pointing out of it is rejected: the file the caller would actually
 * read is what matters, not where the link happens to live.
 *
 * <p>Fails closed. Anything that cannot be resolved or compared is denied.
 */
public final class WorkspaceGuard {

    private final Path root;

    private WorkspaceGuard(Path root) {
        this.root = root;
    }

    /**
     * Creates a guard rooted at {@code root}.
     *
     * @throws IllegalArgumentException if the root does not exist or is not a directory; a guard
     *                                  over a non-existent root would vacuously reject everything
     *                                  and hide the misconfiguration
     */
    public static WorkspaceGuard rootedAt(Path root) {
        Objects.requireNonNull(root, "root");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("workspace root is not a directory: " + normalized);
        }
        try {
            // Resolve the root itself: on macOS /tmp is a symlink to /private/tmp, and comparing a
            // resolved child against an unresolved root would reject every legitimate path.
            return new WorkspaceGuard(normalized.toRealPath());
        } catch (IOException e) {
            throw new IllegalArgumentException("workspace root cannot be resolved: " + normalized, e);
        }
    }

    /** The resolved workspace root. */
    public Path root() {
        return root;
    }

    /**
     * Resolves {@code candidate} against the workspace and verifies containment.
     *
     * @param candidate relative or absolute path from an untrusted caller
     * @return the resolved absolute path, or a stable denial reason
     */
    public Result<Path, String> resolve(String candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.isBlank()) {
            return Result.err("path_blank");
        }
        if (candidate.indexOf('\0') >= 0) {
            // A NUL can truncate the path in native code below us.
            return Result.err("path_contains_nul");
        }

        Path requested;
        try {
            Path raw = Path.of(candidate);
            requested = (raw.isAbsolute() ? raw : root.resolve(raw)).normalize();
        } catch (RuntimeException e) {
            return Result.err("path_invalid");
        }

        Path resolved;
        try {
            // toRealPath follows symlinks, which is the point. For a path that does not exist yet
            // (a file about to be written) resolve the nearest existing ancestor instead, so a
            // symlinked parent directory cannot smuggle the write outside the workspace.
            resolved = Files.exists(requested, LinkOption.NOFOLLOW_LINKS)
                    ? requested.toRealPath()
                    : resolveNearestExistingAncestor(requested);
        } catch (IOException e) {
            return Result.err("path_unresolvable");
        }

        if (!resolved.startsWith(root)) {
            return Result.err("path_outside_workspace");
        }
        return Result.ok(requested);
    }

    /** Convenience predicate. Prefer {@link #resolve} when the reason matters. */
    public boolean contains(String candidate) {
        return resolve(candidate).isOk();
    }

    /**
     * Renders a path relative to the root for display.
     *
     * <p>Host paths are sensitive — they leak usernames and directory layout — so anything shown
     * to a model or written to an event goes through here.
     */
    public String display(Path path) {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();

        // Resolve symlinks before comparing, exactly as containment does. Without this a caller
        // passing an unresolved path (on macOS /var/... for /private/var/...) would be reported as
        // outside the workspace even though it is plainly inside it.
        Path resolved = absolute;
        try {
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                resolved = absolute.toRealPath();
            }
        } catch (IOException e) {
            resolved = absolute; // fall back to the normalized form
        }

        if (resolved.startsWith(root)) {
            return root.relativize(resolved).toString();
        }
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : "<outside-workspace>";
    }

    /**
     * Walks up to the nearest existing ancestor, resolves it through symlinks, then re-appends the
     * non-existent remainder. This is what makes "create a new file" safe to check.
     */
    private Path resolveNearestExistingAncestor(Path requested) throws IOException {
        Path existing = requested;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("no existing ancestor for " + requested);
        }
        Path realExisting = existing.toRealPath();
        Path remainder = existing.relativize(requested);
        return realExisting.resolve(remainder).normalize();
    }
}
