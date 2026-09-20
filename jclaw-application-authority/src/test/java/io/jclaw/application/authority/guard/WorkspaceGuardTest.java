// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.application.authority.guard;

import io.jclaw.ports.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceGuardTest {

    private static String denialFor(WorkspaceGuard guard, String candidate) {
        Result<Path, String> result = guard.resolve(candidate);
        assertTrue(result.isErr(), "expected denial for: " + candidate);
        return result.errorAsOptional().orElseThrow();
    }

    @Test
    @DisplayName("permits paths inside the workspace")
    void permitsInside(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("notes.md"), "hello");
        Files.createDirectories(tmp.resolve("src/main"));
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(tmp);

        assertTrue(guard.resolve("notes.md").isOk());
        assertTrue(guard.resolve("src/main").isOk());
        assertTrue(guard.resolve("./notes.md").isOk());
        assertTrue(guard.resolve("does-not-exist-yet.txt").isOk(), "a file about to be created is fine");
    }

    @Test
    @DisplayName("rejects traversal out of the workspace")
    void rejectsTraversal(@TempDir Path tmp) {
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(tmp);

        assertEquals("path_outside_workspace", denialFor(guard, "../escape.txt"));
        assertEquals("path_outside_workspace", denialFor(guard, "a/b/../../../escape.txt"));
        assertEquals("path_outside_workspace", denialFor(guard, "/etc/passwd"));
    }

    @Test
    @DisplayName("a sibling directory sharing the root's name prefix is not inside it")
    void rejectsPrefixSibling(@TempDir Path tmp) throws IOException {
        // The classic startsWith() bug: "/x/work-evil" starts with "/x/work" as a string.
        Path root = Files.createDirectories(tmp.resolve("work"));
        Path sibling = Files.createDirectories(tmp.resolve("work-evil"));
        Files.writeString(sibling.resolve("secret.txt"), "stolen");
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(root);

        assertEquals("path_outside_workspace",
                denialFor(guard, sibling.resolve("secret.txt").toString()));
    }

    @Test
    @DisplayName("a symlink pointing outside the workspace is rejected")
    void rejectsEscapingSymlink(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("work"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "stolen");

        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support; nothing to assert
        }
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(root);

        // The link lives inside the workspace, but the file it reaches does not.
        assertEquals("path_outside_workspace", denialFor(guard, "link.txt"));
    }

    @Test
    @DisplayName("a write through a symlinked parent directory is rejected")
    void rejectsWriteThroughSymlinkedParent(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("work"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));

        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(root);

        // The target file does not exist yet, so containment rests on resolving the parent.
        assertEquals("path_outside_workspace", denialFor(guard, "escape/new-file.txt"));
    }

    @Test
    @DisplayName("rejects blank paths and embedded NUL")
    void rejectsMalformed(@TempDir Path tmp) {
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(tmp);

        assertEquals("path_blank", denialFor(guard, "  "));
        assertEquals("path_contains_nul",
                denialFor(guard, "ok.txt" + (char) 0 + ".png"));
    }

    @Test
    @DisplayName("display hides absolute host paths")
    void displayHidesHostPaths(@TempDir Path tmp) throws IOException {
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(tmp);
        Files.writeString(tmp.resolve("notes.md"), "x");

        assertEquals("notes.md", guard.display(tmp.resolve("notes.md")));
        assertEquals("<outside-workspace>", guard.display(Path.of("/etc/passwd")));
    }

    @Test
    @DisplayName("a non-directory root is rejected at construction, not silently tolerated")
    void rejectsBadRoot(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("not-a-dir"), "x");

        assertThrows(IllegalArgumentException.class, () -> WorkspaceGuard.rootedAt(file));
        assertThrows(IllegalArgumentException.class,
                () -> WorkspaceGuard.rootedAt(tmp.resolve("missing")));
    }

    @Test
    @DisplayName("contains() agrees with resolve()")
    void containsMatchesResolve(@TempDir Path tmp) {
        WorkspaceGuard guard = WorkspaceGuard.rootedAt(tmp);

        assertTrue(guard.contains("inside.txt"));
        assertFalse(guard.contains("../outside.txt"));
    }
}
