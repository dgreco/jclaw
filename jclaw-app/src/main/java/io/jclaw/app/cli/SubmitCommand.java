// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.runtime.Attachments;
import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.Result;
import io.jclaw.contracts.model.ChatMessage;
import io.jclaw.contracts.turn.ThreadId;
import io.jclaw.contracts.turn.TurnRunId;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Enqueues a turn without executing it: {@code jclaw submit "…"} then {@code jclaw worker}.
 *
 * <p>The asynchronous half of {@code run}. The inbound message and the run record are made
 * durable, nothing else happens, and the command returns the run id at once. A worker with a
 * scheduler executes it under the concurrency cap, one run per thread at a time. This is the
 * shape any non-CLI surface needs, so it exists here first.
 */
@Component
@Command(
        name = "submit",
        description = "Queue a turn for a worker to execute; prints the run id.",
        mixinStandardHelpOptions = true,
        footer = {"", "Execute queued runs with 'jclaw worker' or 'jclaw resume <run-id>'."})
public class SubmitCommand implements Callable<Integer> {

    private final JclawRuntime runtime;

    @Parameters(arity = "1..*", description = "The prompt to send. Multiple words are joined.")
    private String[] prompt;

    @Option(names = {"-t", "--thread"}, description = "Conversation thread. Defaults to 'default'.")
    private String thread = "default";

    @Option(names = "--attach", description = "Attach a file: an image or a UTF-8 text file. Repeatable.")
    private Path[] attach = new Path[0];

    public SubmitCommand(JclawRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        Result<ChatMessage, String> inbound =
                Attachments.userMessage(String.join(" ", prompt), List.of(attach));
        if (inbound instanceof Result.Err<ChatMessage, String> err) {
            System.err.println("jclaw: " + err.error());
            return 1;
        }
        TurnRunId run = runtime.enqueue(new ThreadId(thread),
                ((Result.Ok<ChatMessage, String>) inbound).value());
        System.out.println(run.value());
        return 0;
    }
}
