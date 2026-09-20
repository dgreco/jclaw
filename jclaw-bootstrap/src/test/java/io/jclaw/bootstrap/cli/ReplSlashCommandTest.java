// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slash-command registry and its completer.
 *
 * <p>The property worth pinning is coherence: every registered command must appear in the
 * completion list and in {@code /help}, because a command that exists but is not discoverable
 * while typing defeats the point of the registry.
 */
class ReplSlashCommandTest {

    /** Minimal ParsedLine: the completer only reads word() and wordIndex(). */
    private static ParsedLine line(String word, int wordIndex) {
        return new ParsedLine() {
            @Override public String word() {
                return word;
            }
            @Override public int wordCursor() {
                return word.length();
            }
            @Override public int wordIndex() {
                return wordIndex;
            }
            @Override public List<String> words() {
                return List.of(word);
            }
            @Override public String line() {
                return word;
            }
            @Override public int cursor() {
                return word.length();
            }
        };
    }

    private static List<Candidate> complete(String word, int wordIndex) {
        List<Candidate> candidates = new ArrayList<>();
        ReplCommand.slashCompleter().complete(null, line(word, wordIndex), candidates);
        return candidates;
    }

    @Test
    @DisplayName("typing '/' offers every registered command")
    void slashOffersEveryCommand() {
        List<String> offered = complete("/", 0).stream().map(Candidate::value).toList();

        for (ReplSlashCommand command : ReplSlashCommand.values()) {
            assertTrue(offered.contains(command.token()),
                    command.token() + " must be offered the moment a line starts with '/'");
        }
        assertEquals(ReplSlashCommand.values().length, offered.size(),
                "the completion list is exactly the registry, nothing more");
    }

    @Test
    @DisplayName("every candidate carries a description for the completion menu")
    void candidatesCarryDescriptions() {
        for (Candidate candidate : complete("/", 0)) {
            assertTrue(candidate.descr() != null && !candidate.descr().isBlank(),
                    candidate.value() + " needs a description; a bare name in the menu "
                            + "tells the user nothing");
        }
    }

    @Test
    @DisplayName("prose is left alone: no candidates mid-sentence or for non-slash words")
    void proseIsNotCompleted() {
        assertTrue(complete("hello", 0).isEmpty(),
                "ordinary words must not be completed - Tab in prose would insert surprises");
        assertTrue(complete("/2", 1).isEmpty(),
                "a slash later in the line ('what is 1 /2') is prose, not a command");
    }

    @Test
    @DisplayName("/help lists every command with its description")
    void helpListsEveryCommand() {
        String help = ReplSlashCommand.helpText();

        for (ReplSlashCommand command : ReplSlashCommand.values()) {
            assertTrue(help.contains(command.token()), "/help must list " + command.token());
            assertTrue(help.contains(command.description()),
                    "/help must describe " + command.token());
        }
    }

    @Test
    @DisplayName("lookup resolves exact tokens and rejects everything else")
    void lookupIsExact() {
        assertEquals(ReplSlashCommand.THREAD, ReplSlashCommand.of("/thread").orElseThrow());
        assertTrue(ReplSlashCommand.of("/thre").isEmpty(), "prefixes are completion's job");
        assertTrue(ReplSlashCommand.of("thread").isEmpty(), "the slash is part of the token");
    }
}
