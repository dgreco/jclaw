// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.runtime.RecoveryService;
import io.jclaw.domain.recovery.LeaseRecovery;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Reconciles runs abandoned by a dead worker.
 *
 * <p>Reports every decision, including the ones that change nothing, because "why was my run not
 * recovered" is a real question and silence is a poor answer. A run left in place because its last
 * checkpoint might have had a side effect is a deliberate outcome, not an oversight, and the
 * output says so.
 */
@Component
@Command(
        name = "recover",
        description = "Reconcile runs whose worker died, requeueing only what is safe to replay.",
        mixinStandardHelpOptions = true)
public class RecoverCommand implements Callable<Integer> {

    private final RecoveryService recovery;

    @Option(names = "--dry-run", description = "Report decisions without changing anything.")
    private boolean dryRun;

    public RecoverCommand(RecoveryService recovery) {
        this.recovery = recovery;
    }

    @Override
    public Integer call() {
        List<RecoveryService.Outcome> outcomes = recovery.sweep(dryRun);
        if (outcomes.isEmpty()) {
            System.out.println("(no expired leases)");
            return 0;
        }

        for (RecoveryService.Outcome outcome : outcomes) {
            System.out.println(describe(outcome.decision())
                    + (outcome.applied() ? "" : dryRun ? "  [dry-run]" : "  [no change]"));
        }
        return 0;
    }

    private static String describe(LeaseRecovery.Decision decision) {
        return switch (decision) {
            case LeaseRecovery.Decision.Requeue requeue ->
                    requeue.run().value() + "  REQUEUED  resumable from " + requeue.from();
            case LeaseRecovery.Decision.FailTerminal failed ->
                    failed.run().value() + "  FAILED    " + failed.reason();
            case LeaseRecovery.Decision.LeaveAlone left ->
                    left.run().value() + "  skipped   " + left.reason();
        };
    }
}
