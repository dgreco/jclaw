package io.jclaw.app.cli;

import io.jclaw.app.runtime.JclawRuntime;
import io.jclaw.contracts.capability.ApprovalStore;
import io.jclaw.contracts.turn.GateId;
import io.jclaw.storage.approval.JsonlApprovalStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lists and resolves approval gates.
 *
 * <p>Completes the interactive loop. Without it a run under the default policy parks forever:
 * the gate is raised and recorded correctly, but nothing can answer it.
 *
 * <p>Approving resumes the run by default. Resuming re-dispatches the gated capability, which
 * means the kernel <em>re-authorizes</em> it against the decision just recorded — approval is not
 * assumed by the resume path, it is looked up. Denying therefore resumes into a denial the model
 * is told about, rather than into an effect.
 *
 * <p>Injects the concrete {@link JsonlApprovalStore} rather than the port because listing gates
 * across every scope is a composition-root concern, not part of the kernel's contract.
 */
@Component
@Command(
        name = "approvals",
        description = "List and resolve pending approval gates.",
        mixinStandardHelpOptions = true,
        subcommands = {
                ApprovalsCommand.ListGates.class,
                ApprovalsCommand.Approve.class,
                ApprovalsCommand.Deny.class
        })
public class ApprovalsCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    /** Shows every gate awaiting a decision. */
    @Component
    @Command(name = "list", description = "Show gates awaiting a decision.",
            mixinStandardHelpOptions = true)
    public static class ListGates implements Callable<Integer> {

        private final JsonlApprovalStore approvals;

        public ListGates(JsonlApprovalStore approvals) {
            this.approvals = approvals;
        }

        @Override
        public Integer call() {
            List<ApprovalStore.Gate> pending = approvals.allPending();
            if (pending.isEmpty()) {
                System.out.println("(no pending approvals)");
                return 0;
            }
            for (ApprovalStore.Gate gate : pending) {
                System.out.printf("%s  %s  %s%n",
                        gate.id().value(), gate.raisedAt(), gate.capability().value());
                System.out.println("    run:    " + gate.run().value());
                System.out.println("    scope:  " + gate.scope().lockKey());
                System.out.println("    call:   " + gate.prompt());
                System.out.println();
            }
            System.out.println(pending.size() + " pending. Approve with: jclaw approvals approve <gate-id>");
            return 0;
        }
    }

    /** Approves a gate and, by default, resumes the run it parked. */
    @Component
    @Command(name = "approve", description = "Approve a gate and resume its run.",
            mixinStandardHelpOptions = true)
    public static class Approve implements Callable<Integer> {

        private final JsonlApprovalStore approvals;
        private final JclawRuntime runtime;

        @Parameters(index = "0", description = "Gate id, as shown by 'jclaw approvals list'.")
        private String gateId;

        @Option(names = "--no-resume", description = "Record the decision without resuming the run.")
        private boolean noResume;

        public Approve(JsonlApprovalStore approvals, JclawRuntime runtime) {
            this.approvals = approvals;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            return decide(approvals, runtime, gateId, true, !noResume);
        }
    }

    /** Denies a gate. The run resumes into a denial so the model learns the call was refused. */
    @Component
    @Command(name = "deny", description = "Deny a gate.", mixinStandardHelpOptions = true)
    public static class Deny implements Callable<Integer> {

        private final JsonlApprovalStore approvals;
        private final JclawRuntime runtime;

        @Parameters(index = "0", description = "Gate id, as shown by 'jclaw approvals list'.")
        private String gateId;

        @Option(names = "--resume", description = "Resume the run so the model is told it was denied.")
        private boolean resume;

        public Deny(JsonlApprovalStore approvals, JclawRuntime runtime) {
            this.approvals = approvals;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            return decide(approvals, runtime, gateId, false, resume);
        }
    }

    /** Shared decision path for approve and deny. */
    private static int decide(
            JsonlApprovalStore approvals,
            JclawRuntime runtime,
            String gateId,
            boolean approved,
            boolean resume) {

        Optional<ApprovalStore.Gate> found = approvals.find(new GateId(gateId));
        if (found.isEmpty()) {
            System.err.println("jclaw: no such gate: " + gateId);
            return 1;
        }
        ApprovalStore.Gate gate = found.get();
        if (!gate.isPending()) {
            System.err.println("jclaw: gate already resolved (approved=" + gate.isApproved() + ")");
            return 1;
        }

        approvals.resolve(gate.id(), approved);
        System.out.println((approved ? "Approved" : "Denied") + " " + gate.capability().value());

        if (!resume) {
            System.out.println("Run " + gate.run().value() + " left parked. Resume it with: jclaw resume "
                    + gate.run().value());
            return 0;
        }

        JclawRuntime.TurnResult result = runtime.resume(gate.run(), new AtomicBoolean(false));
        return ResumeCommand.report(result);
    }
}
