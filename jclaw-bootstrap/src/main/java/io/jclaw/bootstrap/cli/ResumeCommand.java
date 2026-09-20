// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.ports.turn.TurnRunId;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continues a run that parked on a gate.
 *
 * <p>Separate from {@code approvals approve} because resuming is not only an approval concern: a
 * run can also be parked by a crash mid-flight, and a checkpoint is a checkpoint regardless of why
 * it was written.
 */
@Component
@Command(
        name = "resume",
        description = "Resume a parked run, or execute a queued one.",
        mixinStandardHelpOptions = true)
public class ResumeCommand implements Callable<Integer> {

    /** Exit code when the run parked again. */
    static final int EXIT_BLOCKED = 2;

    private final JclawRuntime runtime;

    @Parameters(index = "0", description = "Run id, as shown by 'jclaw status'.")
    private String runId;

    public ResumeCommand(JclawRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        return report(runtime.resume(new TurnRunId(runId), new AtomicBoolean(false)));
    }

    /**
     * Renders a turn outcome and maps it to an exit code.
     *
     * <p>Shared with {@code approvals}, so approving-and-resuming and resuming directly report
     * identically — a caller scripting either path sees the same contract.
     */
    static int report(JclawRuntime.TurnResult result) {
        return switch (result.status()) {
            case COMPLETED -> {
                result.reply().ifPresent(System.out::println);
                yield 0;
            }
            case BLOCKED_AUTH -> {
                System.err.println("jclaw: run still needs credentials"
                        + result.gatePrompt().map(g -> " (gate " + g + ")").orElse("")
                        + "; see 'jclaw approvals list'");
                yield EXIT_BLOCKED;
            }
            case WAITING_PROCESS -> {
                System.err.println("jclaw: run still waiting on a child run"
                        + result.gatePrompt().map(g -> " (gate " + g + ")").orElse(""));
                yield EXIT_BLOCKED;
            }
            case BLOCKED_APPROVAL -> {
                System.err.println("jclaw: run parked again"
                        + result.gatePrompt().map(g -> " (gate " + g + ")").orElse(""));
                yield EXIT_BLOCKED;
            }
            case CANCELLED -> {
                System.err.println("jclaw: cancelled");
                yield 1;
            }
            case FAILED, QUEUED, RUNNING -> {
                System.err.println("jclaw: run failed"
                        + result.failure().map(k -> " (" + k.category() + ")").orElse("")
                        + result.failureDetail().map(detail -> ": " + detail).orElse(""));
                yield 1;
            }
        };
    }
}
