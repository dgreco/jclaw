package io.jclaw.app.cli;

import io.jclaw.app.config.JclawProperties;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the REPL's editing keys are actually bound.
 *
 * <p>Worth testing rather than assuming, for two reasons. JLine's emacs defaults could change
 * across versions and silently take the bindings with them. And the arrow keys are genuinely
 * subtle: JLine binds them from the terminfo {@code kcuu1} capability, which on xterm-family
 * terminals is the application-mode form only — the normal-mode form most terminals actually send
 * is unbound by default, which is a real "my arrow keys do nothing" bug.
 */
class ReplBindingsTest {

    /** ASCII escape. Built numerically so this source file stays printable. */
    private static final String ESC = String.valueOf((char) 27);

    private static String ctrl(int code) {
        return String.valueOf((char) code);
    }

    /** A terminal with known capabilities and no TTY, so the test is deterministic. */
    private static Terminal fakeTerminal() throws IOException {
        return TerminalBuilder.builder()
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .type("xterm-256color")
                .build();
    }

    private static JclawProperties properties(Path stateDir) {
        return JclawProperties.defaults(stateDir, stateDir);
    }

    private static KeyMap<Binding> mainKeyMap(Path stateDir) throws IOException {
        try (Terminal terminal = fakeTerminal()) {
            LineReader reader = new ReplCommand(null, properties(stateDir), null).buildReader(terminal);
            return reader.getKeyMaps().get(LineReader.MAIN);
        }
    }

    private static void assertBound(
            KeyMap<Binding> keyMap, String sequence, String widget, String label) {
        Binding binding = keyMap.getBound(sequence);
        assertNotNull(binding, label + " should be bound");
        assertEquals(new Reference(widget), binding, label + " should invoke " + widget);
    }

    @Test
    @DisplayName("Ctrl-A, Ctrl-E, and Ctrl-K are bound to the readline widgets")
    void controlKeysBound(@TempDir Path tmp) throws IOException {
        KeyMap<Binding> keyMap = mainKeyMap(tmp);

        assertBound(keyMap, ctrl(1), LineReader.BEGINNING_OF_LINE, "Ctrl-A");
        assertBound(keyMap, ctrl(5), LineReader.END_OF_LINE, "Ctrl-E");
        assertBound(keyMap, ctrl(11), LineReader.KILL_LINE, "Ctrl-K");
    }

    @Test
    @DisplayName("arrow keys are bound in BOTH normal and application cursor modes")
    void arrowKeysBoundInBothModes(@TempDir Path tmp) throws IOException {
        KeyMap<Binding> keyMap = mainKeyMap(tmp);

        // Normal mode (ESC [ X) — what most terminals send, and unbound by JLine's default.
        assertBound(keyMap, ESC + "[A", LineReader.UP_LINE_OR_SEARCH, "Up (normal mode)");
        assertBound(keyMap, ESC + "[B", LineReader.DOWN_LINE_OR_SEARCH, "Down (normal mode)");
        assertBound(keyMap, ESC + "[C", LineReader.FORWARD_CHAR, "Right (normal mode)");
        assertBound(keyMap, ESC + "[D", LineReader.BACKWARD_CHAR, "Left (normal mode)");

        // Application mode (ESC O X) — what a terminal sends after JLine emits smkx.
        assertBound(keyMap, ESC + "OA", LineReader.UP_LINE_OR_SEARCH, "Up (application mode)");
        assertBound(keyMap, ESC + "OB", LineReader.DOWN_LINE_OR_SEARCH, "Down (application mode)");
        assertBound(keyMap, ESC + "OC", LineReader.FORWARD_CHAR, "Right (application mode)");
        assertBound(keyMap, ESC + "OD", LineReader.BACKWARD_CHAR, "Left (application mode)");
    }

    @Test
    @DisplayName("the rest of emacs mode comes along: Ctrl-W, Ctrl-U, Ctrl-Y")
    void emacsModeIntact(@TempDir Path tmp) throws IOException {
        KeyMap<Binding> keyMap = mainKeyMap(tmp);

        // Not part of the request, but their presence is what confirms emacs mode is active
        // rather than a hand-rolled keymap containing only the requested keys.
        assertNotNull(keyMap.getBound(ctrl(23)), "Ctrl-W (backward kill word) should be bound");
        assertNotNull(keyMap.getBound(ctrl(21)), "Ctrl-U should be bound");
        assertNotNull(keyMap.getBound(ctrl(25)), "Ctrl-Y (yank) should be bound");
    }

    @Test
    @DisplayName("history expansion is disabled so '!' in a prompt stays literal")
    void eventExpansionDisabled(@TempDir Path tmp) throws IOException {
        try (Terminal terminal = fakeTerminal()) {
            LineReader reader = new ReplCommand(null, properties(tmp), null).buildReader(terminal);

            assertTrue(reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION),
                    "prompts are prose; '!' must not trigger bash-style history expansion");
        }
    }

    @Test
    @DisplayName("the slash key is bound to the widget that lists commands as you start typing")
    void slashKeyBoundToListingWidget(@TempDir Path tmp) throws IOException {
        try (Terminal terminal = fakeTerminal()) {
            LineReader reader = new ReplCommand(null, properties(tmp), null).buildReader(terminal);

            KeyMap<Binding> keyMap = reader.getKeyMaps().get(LineReader.MAIN);
            assertBound(keyMap, "/", ReplCommand.SLASH_LIST_WIDGET, "the '/' key");
            assertNotNull(reader.getWidgets().get(ReplCommand.SLASH_LIST_WIDGET),
                    "the widget the '/' key references must actually be registered");
        }
    }

    @Test
    @DisplayName("a dumb terminal has no editing keymap, and binding is a safe no-op")
    void dumbTerminalDegradesGracefully() throws IOException {
        // Piped input builds a dumb terminal. bindArrowKeys must tolerate the missing keymap
        // rather than throwing, or every scripted REPL run would fail at startup.
        try (Terminal dumb = TerminalBuilder.builder()
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                .type(Terminal.TYPE_DUMB)
                .dumb(true)
                .build()) {

            LineReader reader = LineReaderBuilder.builder().terminal(dumb).build();

            ReplCommand.bindArrowKeys(reader); // must not throw
            ReplCommand.bindSlashListing(reader); // must not throw either
        }
    }
}
