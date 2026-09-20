// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.config.JclawProperties;
import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.ports.capability.ApprovalStore;
import io.jclaw.ports.model.ModelProvider;
import io.jclaw.ports.turn.GateId;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.adapter.out.persistence.approval.JsonlApprovalStore;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Interactive multi-turn session with full line editing.
 *
 * <p>Backed by JLine in <b>emacs editing mode</b>, which is where the standard readline bindings
 * come from — the same ones bash, psql, and every other readline-driven prompt use:
 *
 * <table border="1">
 *   <caption>Editing keys</caption>
 *   <tr><td>Up / Down</td><td>previous / next history entry</td></tr>
 *   <tr><td>Left / Right</td><td>move the cursor by one character</td></tr>
 *   <tr><td>Ctrl-A / Ctrl-E</td><td>start / end of line</td></tr>
 *   <tr><td>Ctrl-K</td><td>kill from the cursor to end of line</td></tr>
 *   <tr><td>Ctrl-W, Alt-B, Alt-F, Ctrl-U, Ctrl-Y</td><td>the rest of emacs mode, for free</td></tr>
 *   <tr><td>Ctrl-C</td><td>abandon the current line, keep the session</td></tr>
 *   <tr><td>Ctrl-D</td><td>exit</td></tr>
 * </table>
 *
 * <p>These are not hand-bound. Rebinding them individually would mean reimplementing a keymap
 * that JLine already ships correctly, and would silently diverge from what a user's muscle memory
 * expects for every key not on the list. {@code ReplBindingsTest} asserts they are present rather
 * than trusting the default to stay put.
 *
 * <p><b>Piped input still works.</b> The terminal is built with {@code dumb(true)}, so when stdin
 * is not a TTY — a script, a test — JLine degrades to plain line reading instead of failing. The
 * integration tests drive this command that way, and losing that would make the REPL untestable.
 *
 * <p><b>Event expansion is disabled.</b> By default JLine performs bash-style {@code !} history
 * expansion, which would quietly mangle any prompt containing an exclamation mark — a real hazard
 * when the input is English prose rather than shell commands.
 *
 * <p><b>Slash commands are session controls, not prompts.</b> {@code /help}, {@code /thread},
 * {@code /new}, {@code /stream}, {@code /tools}, {@code /exit} — see {@link ReplSlashCommand}.
 * Typing {@code /} as the first character of a line lists them immediately (a widget bound to the
 * slash key invokes JLine's {@code list-choices}); Tab completes them with their descriptions.
 * A line starting with {@code /} never reaches the model — an unknown command is a hint, because
 * a mistyped control being answered by the model is more confusing than a one-line correction.
 */
@Component
@Command(
        name = "repl",
        description = "Start an interactive session. Type 'exit' or press Ctrl-D to quit.",
        mixinStandardHelpOptions = true,
        footer = {
                "",
                "Global options (--debug, --trace, --jclaw.* overrides) work here too;",
                "see 'jclaw --help' for the full list."
        })
public class ReplCommand implements Callable<Integer> {

    /** Entries kept in memory and on disk. */
    private static final int HISTORY_SIZE = 1000;

    /**
     * Lines never written to the history file: anything that looks like it carries a credential.
     *
     * <p>Colon-separated globs, JLine's own format. Best effort, not a guarantee — the real
     * control is that a user should not paste a key into a prompt. Note that a line kept out of
     * the history file is still sent to the model and still recorded in the transcript; this only
     * keeps it out of a long-lived file that outlives the conversation.
     */
    private static final String HISTORY_IGNORE =
            "*sk-ant-*:*sk-*:*ghp_*:*_API_KEY=*:*_TOKEN=*:*password*";

    private final JclawRuntime runtime;
    private final JclawProperties properties;
    private final JsonlApprovalStore approvals;

    @Option(
            names = {"-t", "--thread"},
            description = "Conversation thread to use. Defaults to 'default'.")
    private String thread = "default";

    @Option(names = "--quiet", description = "Suppress the banner and prompt markers.")
    private boolean quiet;

    @Option(names = "--stream", description = "Print replies as they are generated.")
    private boolean stream;

    @Option(names = "--no-history", description = "Do not read or write the persistent history file.")
    private boolean noHistory;

    public ReplCommand(JclawRuntime runtime, JclawProperties properties, JsonlApprovalStore approvals) {
        this.approvals = approvals;
        this.runtime = runtime;
        this.properties = properties;
    }

    @Override
    public Integer call() throws IOException {
        try (Terminal terminal = buildTerminal()) {
            LineReader reader = buildReader(terminal);

            if (!quiet) {
                terminal.writer().println("jclaw repl - thread '" + thread + "'.");
                terminal.writer().println(
                        terminal.getType().equals(Terminal.TYPE_DUMB)
                                ? "(dumb terminal: line editing unavailable; /help lists commands)"
                                : "Type / to list commands. Arrow keys, Ctrl-A/E/K, and history "
                                        + "are available. Ctrl-D to exit.");
                terminal.writer().flush();
            }

            loop(reader, terminal);
        }
        return 0;
    }

    private void loop(LineReader reader, Terminal terminal) {
        String prompt = quiet ? "" : "> ";

        while (true) {
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                // Ctrl-C abandons the line being typed, like a shell. Ending the whole session
                // here would lose the conversation over a mistyped character.
                continue;
            } catch (EndOfFileException e) {
                break; // Ctrl-D, or piped input exhausted
            }

            if (line == null) {
                break;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                break;
            }
            if (input.startsWith("/")) {
                if (!dispatchSlash(input, terminal)) {
                    break;
                }
                continue;
            }

            // The thread is re-read each turn because /thread and /new change it mid-session.
            JclawRuntime.TurnResult result = runtime.submit(
                    new ThreadId(thread), input, new AtomicBoolean(false), streamSink(terminal));

            report(terminal, reader, result);
        }

        if (!quiet) {
            terminal.writer().println("bye");
            terminal.writer().flush();
        }
    }

    /**
     * Executes one slash command.
     *
     * @return whether the session continues; {@code false} only for {@code /exit} and {@code /quit}
     */
    private boolean dispatchSlash(String input, Terminal terminal) {
        String[] words = input.split("\\s+", 2);
        String argument = words.length > 1 ? words[1].strip() : "";

        Optional<ReplSlashCommand> command = ReplSlashCommand.of(words[0]);
        if (command.isEmpty()) {
            terminal.writer().println("jclaw: unknown command " + words[0] + " - type /help");
            terminal.writer().flush();
            return true;
        }

        switch (command.get()) {
            case EXIT, QUIT -> {
                return false;
            }
            case HELP -> terminal.writer().print(ReplSlashCommand.helpText());
            case TOOLS -> runtime.visibleCapabilities().forEach(descriptor ->
                    terminal.writer().println("  " + descriptor.id().value()
                            + "  (" + descriptor.effect() + ", " + descriptor.trust() + ")"));
            case NEW -> {
                thread = "t-" + UUID.randomUUID().toString().substring(0, 8);
                terminal.writer().println("switched to fresh thread '" + thread + "'");
            }
            case THREAD -> {
                if (argument.isEmpty()) {
                    terminal.writer().println("thread '" + thread + "'");
                } else {
                    thread = argument;
                    terminal.writer().println("switched to thread '" + thread + "'");
                }
            }
            case STREAM -> {
                if (argument.isEmpty()) {
                    stream = !stream;
                } else if (argument.equalsIgnoreCase("on") || argument.equalsIgnoreCase("off")) {
                    stream = argument.equalsIgnoreCase("on");
                } else {
                    terminal.writer().println("usage: /stream [on|off]");
                    terminal.writer().flush();
                    return true;
                }
                terminal.writer().println("streaming " + (stream ? "on" : "off"));
            }
        }
        terminal.writer().flush();
        return true;
    }

    /**
     * Prints the outcome, and asks about a gate when there is someone to ask.
     *
     * @return whether the gate was put to the user rather than printed as a command to run
     *         elsewhere. The session loop ignores it; a test uses it to tell the two apart
     *         without depending on terminal output, which JLine writes asynchronously
     */
    boolean report(Terminal terminal, LineReader reader, JclawRuntime.TurnResult result) {
        if (stream && result.isSuccess()) {
            // Already printed as it arrived; a second copy would duplicate the whole reply.
            terminal.writer().println();
            terminal.writer().flush();
            return false;
        }
        if (result.reply().isPresent()) {
            terminal.writer().println(result.reply().get());
            terminal.writer().flush();
            return false;
        }

        Optional<ApprovalStore.Gate> gate = result.gatePrompt()
                .map(GateId::new)
                .flatMap(approvals::find)
                .filter(ApprovalStore.Gate::isPending)
                .filter(pending -> !approvals.expired(pending));
        boolean canAsk = gate.isPresent() && !terminal.getType().equals(Terminal.TYPE_DUMB);

        terminal.writer().println("jclaw: " + result.status()
                + result.failure().map(kind -> " (" + kind.category() + ")").orElse("")
                + result.failureDetail().map(detail -> ": " + detail).orElse("")
                + (canAsk ? "" : result.gatePrompt()
                        .map(id -> " - resolve with: jclaw approvals approve " + id)
                        .orElse("")));
        terminal.writer().flush();

        if (canAsk) {
            resolveInline(terminal, reader, gate.get());
        }
        return canAsk;
    }

    /**
     * Asks about a gate where the user already is, and resumes the run with the answer.
     *
     * <p>Only on a real terminal. With piped input the next line is the next prompt, not an
     * answer to a question nobody saw, so a scripted session keeps the printed command and
     * behaves exactly as before.
     *
     * <p>The kernel re-authorizes on resume regardless: answering here writes a decision to the
     * approval store, and the resumed run asks the store again. Nothing about the trust model
     * changes because the question was asked in a nicer place.
     */
    private void resolveInline(Terminal terminal, LineReader reader, ApprovalStore.Gate gate) {
        terminal.writer().println("  " + gate.prompt());
        terminal.writer().flush();

        String answer;
        try {
            answer = reader.readLine("approve? [y]es / [n]o / [l]ater: ");
        } catch (UserInterruptException | EndOfFileException e) {
            // Ctrl-C or Ctrl-D at the question is not an answer, and must never read as one.
            answer = "later";
        }
        applyDecision(terminal, reader, gate, answer);
    }

    /**
     * Acts on the answer. Package-private so a test can supply what a terminal would have read.
     *
     * <p>Anything that is not a clear yes or no leaves the run parked, including an empty line:
     * a gate is a question about an effect, and silence is not consent.
     */
    void applyDecision(Terminal terminal, LineReader reader, ApprovalStore.Gate gate, String answer) {
        String choice = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        if (!choice.startsWith("y") && !choice.startsWith("n")) {
            terminal.writer().println("left parked; resolve with: jclaw approvals approve " + gate.id().value());
            terminal.writer().flush();
            return;
        }
        boolean approved = choice.startsWith("y");
        approvals.resolve(gate.id(), approved);
        terminal.writer().println((approved ? "approved " : "denied ") + gate.capability().value()
                + "; resuming " + gate.run().value());
        terminal.writer().flush();

        // A denial resumes too: the model is told, and the turn continues without the effect.
        report(terminal, reader, runtime.resume(gate.run(), new AtomicBoolean(false)));
    }

    /**
     * Builds the terminal.
     *
     * <p>{@code dumb(true)} is what keeps piped input working: without it JLine throws when there
     * is no TTY, which would break every scripted use and every test.
     */
    private static Terminal buildTerminal() throws IOException {
        return TerminalBuilder.builder()
                .name("jclaw")
                .dumb(true)
                .build();
    }

    /** Package-visible so tests assert the real configuration rather than a reconstruction. */
    LineReader buildReader(Terminal terminal) {
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("jclaw")
                .completer(slashCompleter())
                .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_SIZE)
                .variable(LineReader.HISTORY_IGNORE, HISTORY_IGNORE)
                // Prompts are prose, not shell. '!' must stay a literal exclamation mark.
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                // Familiar shell convention: a line typed with a leading space is not recorded.
                .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                // Ambiguous completion shows the whole list rather than just beeping.
                .option(LineReader.Option.AUTO_LIST, true);

        historyFile().ifPresent(path -> builder.variable(LineReader.HISTORY_FILE, path));

        LineReader reader = builder.build();
        bindArrowKeys(reader);
        bindSlashListing(reader);
        return reader;
    }

    /**
     * Completes slash commands, and only those.
     *
     * <p>Restricted to the first word of a line that starts with {@code /}: prompts are prose, and
     * a completer that fires on ordinary words would turn Tab-as-typo into surprise insertions.
     * Each candidate carries the command's argument summary and description, which JLine renders
     * next to the name in the completion list.
     */
    static Completer slashCompleter() {
        return (reader, line, candidates) -> {
            if (line.wordIndex() != 0 || !line.word().startsWith("/")) {
                return;
            }
            for (ReplSlashCommand command : ReplSlashCommand.values()) {
                String hint = command.arguments().isEmpty()
                        ? command.description()
                        : command.arguments() + "  " + command.description();
                candidates.add(new Candidate(
                        command.token(), command.token(), null, hint, null, null, true));
            }
        };
    }

    /** Widget name for the slash key, asserted by {@code ReplBindingsTest}. */
    static final String SLASH_LIST_WIDGET = "jclaw-slash-list";

    /**
     * Makes the command list appear the moment a line starts with {@code /}.
     *
     * <p>The slash key is bound to a widget that self-inserts and then, only when the slash is the
     * first and only character, invokes JLine's {@code list-choices} — the same display Tab would
     * produce, without requiring the user to know to press Tab. A slash anywhere else in a line
     * ({@code "what is 1/2?"}) just inserts, so prose is unaffected.
     */
    static void bindSlashListing(LineReader reader) {
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        if (main == null) {
            return; // a dumb terminal has no editing keymap and cannot render a listing
        }
        reader.getWidgets().put(SLASH_LIST_WIDGET, () -> {
            reader.getBuffer().write('/');
            if (reader.getBuffer().toString().equals("/")) {
                reader.callWidget(LineReader.LIST_CHOICES);
            }
            return true;
        });
        main.bind(new Reference(SLASH_LIST_WIDGET), "/");
    }

    /**
     * Binds both escape-sequence forms for the arrow keys.
     *
     * <p>JLine binds arrows from the terminfo {@code kcuu1} capability, which for xterm-family
     * terminals is the <em>application</em> cursor form {@code ESC O A}. That is only what a
     * terminal sends after it has been switched into application-keypad mode, which JLine does by
     * emitting {@code smkx} when it takes control. When that does not take effect — a terminal
     * that ignores {@code smkx}, a multiplexer that filters it, an unusual {@code TERM} — the
     * terminal sends the normal-mode form {@code ESC [ A} instead, and with only one form bound
     * the arrow keys silently do nothing.
     *
     * <p>Binding both costs nothing and removes a whole class of "arrows don't work in my
     * terminal" reports. The widgets are JLine's own defaults, so behaviour is unchanged where the
     * default already worked.
     */
    static void bindArrowKeys(LineReader reader) {
        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        if (main == null) {
            return; // a dumb terminal has no editing keymap; nothing to bind
        }
        bindBothForms(main, LineReader.UP_LINE_OR_SEARCH, 'A');
        bindBothForms(main, LineReader.DOWN_LINE_OR_SEARCH, 'B');
        bindBothForms(main, LineReader.FORWARD_CHAR, 'C');
        bindBothForms(main, LineReader.BACKWARD_CHAR, 'D');
    }

    /** Binds {@code ESC [ <final>} and {@code ESC O <final>} to the same widget. */
    private static void bindBothForms(KeyMap<Binding> keyMap, String widget, char finalByte) {
        String escape = String.valueOf((char) 27);
        keyMap.bind(new Reference(widget), escape + "[" + finalByte, escape + "O" + finalByte);
    }

    /** Where history is persisted, unless {@code --no-history} was passed. */
    private Optional<Path> historyFile() {
        if (noHistory) {
            return Optional.empty();
        }
        Path path = properties.replHistoryPath();
        try {
            Files.createDirectories(path.getParent());
            return Optional.of(path);
        } catch (IOException e) {
            // History is a convenience. Losing it must not stop the session starting.
            return Optional.empty();
        }
    }

    /** Prints prose deltas to the terminal as they arrive. */
    private Optional<Consumer<ModelProvider.StreamEvent>> streamSink(Terminal terminal) {
        if (!stream) {
            return Optional.empty();
        }
        return Optional.of(event -> {
            if (event instanceof ModelProvider.StreamEvent.TextDelta delta) {
                terminal.writer().print(delta.text());
                terminal.writer().flush();
            }
        });
    }
}
