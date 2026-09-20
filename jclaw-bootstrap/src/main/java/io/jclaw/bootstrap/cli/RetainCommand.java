// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.runtime.RetentionService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Drops old rows of finished runs from the stores that grow without bound.
 *
 * <p>Reports every store, including the ones set to keep forever, so "why is this file still
 * huge" has an answer in the output rather than in the configuration.
 */
@Component
@Command(
        name = "retain",
        description = "Apply retention: drop old results, events, and checkpoints of finished runs.",
        mixinStandardHelpOptions = true)
public class RetainCommand implements Callable<Integer> {

    private final RetentionService retention;

    @Option(names = "--dry-run", description = "Count what would be dropped without rewriting anything.")
    private boolean dryRun;

    public RetainCommand(RetentionService retention) {
        this.retention = retention;
    }

    @Override
    public Integer call() {
        for (RetentionService.Swept swept : retention.sweep(dryRun)) {
            System.out.printf("%-12s kept %-6d dropped %-6d %s%n",
                    swept.store(), swept.kept(), swept.dropped(),
                    swept.dropped() == 0 ? "" : swept.applied() ? "[rewritten]" : "[dry-run]");
        }
        return 0;
    }
}
