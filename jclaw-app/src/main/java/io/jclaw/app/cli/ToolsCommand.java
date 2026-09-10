// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.app.cli;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.capability.CapabilityDescriptor;
import io.jclaw.kernel.capability.CapabilityPolicy;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists the capability surface.
 *
 * <p>Shows effect and trust class alongside each capability, and marks which ones run unattended
 * under the current policy. That last column is the point: "what can this agent do without asking
 * me" is the question an operator actually needs answered, and it depends on policy, not just on
 * what is installed.
 */
@Component
@Command(
        name = "tools",
        description = "List available capabilities and their effect classes.",
        mixinStandardHelpOptions = true)
public class ToolsCommand implements Callable<Integer> {

    private final JclawRuntime runtime;
    private final CapabilityPolicy policy;

    @Option(names = "--verbose", description = "Include argument schemas.")
    private boolean verbose;

    public ToolsCommand(JclawRuntime runtime, CapabilityPolicy policy) {
        this.runtime = runtime;
        this.policy = policy;
    }

    @Override
    public Integer call() {
        List<CapabilityDescriptor> descriptors = runtime.visibleCapabilities();
        if (descriptors.isEmpty()) {
            System.out.println("(no capabilities registered)");
            return 0;
        }

        int widest = descriptors.stream()
                .mapToInt(descriptor -> descriptor.id().value().length())
                .max()
                .orElse(20);

        // Built once: the width is the only variable in it, and two copies of a format string
        // are two chances for the header to stop lining up with the rows under it.
        String row = "%-" + widest + "s  %-12s  %-12s  %s%n";

        System.out.printf(row, "CAPABILITY", "EFFECT", "TRUST", "UNATTENDED");
        for (CapabilityDescriptor descriptor : descriptors) {
            System.out.printf(row,
                    descriptor.id().value(),
                    descriptor.effect(),
                    descriptor.trust(),
                    // Consult the active policy, not just the capability's own trust ceiling:
                    // the honest answer to "does this ask me first" depends on both.
                    policy.permitsUnattended(descriptor) ? "yes" : "needs approval");
            if (verbose) {
                System.out.println("    " + descriptor.description());
                System.out.println("    schema: " + descriptor.inputSchema());
            }
        }
        return 0;
    }
}
