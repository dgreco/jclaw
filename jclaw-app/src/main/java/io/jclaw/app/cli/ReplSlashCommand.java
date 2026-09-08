package io.jclaw.app.cli;

import java.util.Arrays;
import java.util.Optional;

/**
 * The REPL's slash commands: session controls, distinct from prompts.
 *
 * <p>One enum feeds three surfaces — dispatch, tab completion, and {@code /help} — so the list a
 * user sees while typing can never disagree with what the loop actually accepts. Adding a command
 * here is the whole registration; forgetting one of the three surfaces stops being possible.
 *
 * <p>A line starting with {@code /} is always treated as a command and never sent to the model: an
 * unknown command earns a hint, not a model turn. A prompt that genuinely needs to begin with a
 * slash can be prefixed with a space, the same convention that keeps a line out of history.
 */
public enum ReplSlashCommand {

    HELP("/help", "", "List available commands."),
    TOOLS("/tools", "", "List capabilities visible to the model."),
    THREAD("/thread", "[id]", "Show the current conversation thread, or switch to another."),
    NEW("/new", "", "Start a fresh conversation thread."),
    STREAM("/stream", "[on|off]", "Toggle streaming replies."),
    EXIT("/exit", "", "Leave the session."),
    QUIT("/quit", "", "Leave the session.");

    private final String token;
    private final String arguments;
    private final String description;

    ReplSlashCommand(String token, String arguments, String description) {
        this.token = token;
        this.arguments = arguments;
        this.description = description;
    }

    /** The literal the user types, including the slash. */
    public String token() {
        return token;
    }

    /** Argument summary for help and completion hints; empty when the command takes none. */
    public String arguments() {
        return arguments;
    }

    public String description() {
        return description;
    }

    /** Resolves the first word of a slash line — {@code "/thread work"} resolves to THREAD. */
    public static Optional<ReplSlashCommand> of(String word) {
        return Arrays.stream(values())
                .filter(command -> command.token.equals(word))
                .findFirst();
    }

    /** The {@code /help} table. */
    public static String helpText() {
        StringBuilder help = new StringBuilder("Commands:").append(System.lineSeparator());
        for (ReplSlashCommand command : values()) {
            String usage = (command.token + " " + command.arguments).strip();
            help.append("  ")
                    .append(String.format("%-18s", usage))
                    .append(command.description)
                    .append(System.lineSeparator());
        }
        return help.toString();
    }
}
