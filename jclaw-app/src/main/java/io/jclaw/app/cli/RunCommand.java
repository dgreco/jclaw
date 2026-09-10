// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.model.ModelProvider;
import io.jclaw.contracts.turn.ThreadId;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot turn: {@code jclaw run "summarize README.md"}.
 *
 * <p>Exit codes are meaningful because this command is meant to be scripted — 0 on a completed
 * turn, 1 on failure, 2 when the run parked on an approval gate. A caller in a pipeline can act on
 * the distinction without parsing output.
 */
@Component
@Command(
        name = "run",
        description = "Run a single turn and print the reply.",
        mixinStandardHelpOptions = true,
        footer = {
                "",
                "Exit codes: 0 completed, 1 failed, 2 parked on an approval gate.",
                "Global options (--debug, --trace, --jclaw.* overrides) work here too;",
                "see 'jclaw --help' for the full list."
        })
public class RunCommand implements Callable<Integer> {

    /** Exit code when the run parked awaiting a human decision. */
    private static final int EXIT_BLOCKED = 2;

    private final JclawRuntime runtime;

    @Parameters(
            arity = "1..*",
            description = "The prompt to send. Multiple words are joined.")
    // An array rather than a List: picocli's collection path appends into the existing
    // value, which fails when the bean is a Spring singleton whose field it cannot grow.
    private String[] prompt;

    @Option(names = "--stream", description = "Print the reply as it is generated.")
    private boolean stream;

    @Option(names = "--attach", description = "Attach a file: an image (png, jpg, gif, webp) or a UTF-8 text file. Repeatable.")
    private java.nio.file.Path[] attach = new java.nio.file.Path[0];

    @Option(
            names = {"-t", "--thread"},
            description = "Conversation thread to continue. Defaults to 'default'.")
    private String thread = "default";

    public RunCommand(JclawRuntime runtime) {
        this.runtime = runtime;
    }

    /** Prints prose deltas as they arrive. Tool calls are not streamed — see the provider. */
    private java.util.Optional<java.util.function.Consumer<ModelProvider.StreamEvent>> streamSink() {
        if (!stream) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(event -> {
            if (event instanceof ModelProvider.StreamEvent.TextDelta delta) {
                System.out.print(delta.text());
                System.out.flush();
            }
        });
    }

    @Override
    public Integer call() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread interrupt = new Thread(() -> cancelled.set(true), "jclaw-cancel");
        Runtime.getRuntime().addShutdownHook(interrupt);

        try {
            io.jclaw.contracts.Result<io.jclaw.contracts.model.ChatMessage, String> inbound =
                    io.jclaw.app.runtime.Attachments.userMessage(String.join(" ", prompt), java.util.List.of(attach));
            if (inbound instanceof io.jclaw.contracts.Result.Err<io.jclaw.contracts.model.ChatMessage, String> err) {
                System.err.println("jclaw: " + err.error());
                return 1;
            }
            JclawRuntime.TurnResult result = runtime.submit(
                    new ThreadId(thread),
                    ((io.jclaw.contracts.Result.Ok<io.jclaw.contracts.model.ChatMessage, String>) inbound).value(),
                    cancelled, streamSink());

            return switch (result.status()) {
                case COMPLETED -> {
                    // When streaming, the text has already been printed as it arrived; printing
                    // the reply again would duplicate the whole answer.
                    if (stream) {
                        System.out.println();
                    } else {
                        result.reply().ifPresent(System.out::println);
                    }
                    yield 0;
                }
                case BLOCKED_AUTH -> {
                    System.err.println("jclaw: run parked awaiting credentials"
                            + result.gatePrompt().map(gate -> " (gate " + gate + ")").orElse(""));
                    System.err.println("See 'jclaw approvals list' for what is missing, set it, then: "
                            + "jclaw resume " + result.run().value());
                    yield EXIT_BLOCKED;
                }
                case WAITING_PROCESS -> {
                    System.err.println("jclaw: run waiting on a child run"
                            + result.gatePrompt().map(gate -> " (gate " + gate + ")").orElse(""));
                    System.err.println("A running 'jclaw worker' (or 'jclaw serve') executes the child and "
                            + "resumes this run; or resume it yourself once the child has finished.");
                    yield EXIT_BLOCKED;
                }
                case BLOCKED_APPROVAL -> {
                    System.err.println("jclaw: run parked awaiting approval"
                            + result.gatePrompt().map(gate -> " (gate " + gate + ")").orElse(""));
                    System.err.println("Re-run with --approval-mode trusted, or approve the gate.");
                    yield EXIT_BLOCKED;
                }
                case CANCELLED -> {
                    System.err.println("jclaw: cancelled");
                    yield 1;
                }
                case FAILED, QUEUED, RUNNING -> {
                    // Include the provider's explanation. Printing only the category means the
                    // user has to know to run 'jclaw status' to learn what actually went wrong.
                    System.err.println("jclaw: run failed"
                            + result.failure().map(kind -> " (" + kind.category() + ")").orElse("")
                            + result.failureDetail().map(detail -> ": " + detail).orElse(""));
                    yield 1;
                }
            };
        } finally {
            // Removing the hook matters: leaving it registered would fire on normal exit too.
            try {
                Runtime.getRuntime().removeShutdownHook(interrupt);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Shutdown already in progress; nothing to remove.
            }
        }
    }
}
