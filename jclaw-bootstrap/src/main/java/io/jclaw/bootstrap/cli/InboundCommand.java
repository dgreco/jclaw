// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.bootstrap.cli;

import io.jclaw.bootstrap.runtime.JclawRuntime;
import io.jclaw.ports.inbound.InboundReviewStore;
import io.jclaw.ports.model.ChatMessage;
import io.jclaw.ports.turn.ThreadId;
import io.jclaw.domain.safety.InboundScreening;
import io.jclaw.domain.safety.InjectionHeuristics;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The review queue: foreign messages the inbound policy held back for a person.
 *
 * <p>Only populated under {@code inbound-policy: review}, and only by content that scored
 * {@code HIGH}. A held message has started nothing — no run, no thread lock, no transcript entry
 * — so discarding one leaves only the record that it arrived, and approving is what creates the
 * turn.
 *
 * <p>Approving does not mean "treat this as instructions". The message is enqueued fenced,
 * exactly as {@code sanitize} would have delivered it: a reviewer is saying it is worth
 * answering, which is a different judgement from trusting it.
 */
@Component
@Command(
        name = "inbound",
        description = "Review foreign messages held by the inbound policy.",
        mixinStandardHelpOptions = true,
        subcommands = {InboundCommand.ListAll.class, InboundCommand.Approve.class,
                InboundCommand.Discard.class})
public class InboundCommand implements Runnable {

    @Override
    public void run() {
        new picocli.CommandLine(this).usage(System.out);
    }

    @Component
    @Command(name = "list", description = "Messages awaiting a decision.", mixinStandardHelpOptions = true)
    public static class ListAll implements Callable<Integer> {

        private final InboundReviewStore review;
        private final JclawRuntime runtime;

        @Option(names = "--full", description = "Print each message in full rather than a first line.")
        private boolean full;

        public ListAll(InboundReviewStore review, JclawRuntime runtime) {
            this.review = review;
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            List<InboundReviewStore.Held> pending =
                    review.pending(runtime.scopeFor(new ThreadId("inbound")));
            if (pending.isEmpty()) {
                System.out.println("(nothing held)");
                return 0;
            }
            for (InboundReviewStore.Held held : pending) {
                System.out.printf("%-36s %-14s %-6s %s%n", held.id(), held.source(),
                        held.severity(), held.receivedAt());
                System.out.println("    thread: " + held.thread().value()
                        + "   matched: " + String.join(", ", held.rules()));
                // The text as it arrived, never the fenced copy: a reviewer has to see what was
                // actually sent, and showing them the framing would hide what they are judging.
                String text = held.text();
                if (full) {
                    System.out.println(text.lines().map(line -> "    | " + line)
                            .reduce((a, b) -> a + "\n" + b).orElse("    | (empty)"));
                } else {
                    String firstLine = text.lines().findFirst().orElse("");
                    System.out.println("    | " + (firstLine.length() > 100
                            ? firstLine.substring(0, 100) + "…" : firstLine));
                }
            }
            System.out.println();
            System.out.println(pending.size() + " held. Approve one with: jclaw inbound approve <id>");
            return 0;
        }
    }

    @Component
    @Command(name = "approve", description = "Enqueue a held message as a turn. It is still fenced.",
            mixinStandardHelpOptions = true)
    public static class Approve implements Callable<Integer> {

        private final InboundReviewStore review;
        private final JclawRuntime runtime;
        private final Clock clock;

        @Parameters(index = "0", description = "The held message id.")
        private String id;

        public Approve(InboundReviewStore review, JclawRuntime runtime, Clock clock) {
            this.review = review;
            this.runtime = runtime;
            this.clock = clock;
        }

        @Override
        public Integer call() {
            var decided = review.decide(id, true, clock.instant());
            if (decided.isEmpty()) {
                System.err.println("jclaw: no message held under '" + id + "' (already decided?)");
                return 1;
            }
            InboundReviewStore.Held held = decided.get();
            // Fenced on the way in, exactly as sanitize would have delivered it. Approving says
            // the message is worth answering; it does not promote a stranger to principal.
            String fenced = InboundScreening.fence(held.text(), held.source(),
                    InjectionHeuristics.scan(held.text()));
            var run = runtime.enqueue(held.thread(), ChatMessage.user(fenced));
            System.out.println("Approved " + held.id() + " -> " + run.value()
                    + " on " + held.thread().value());
            System.out.println("The message was enqueued fenced, as data rather than instructions.");
            return 0;
        }
    }

    @Component
    @Command(name = "discard", description = "Drop a held message. No turn is created.",
            mixinStandardHelpOptions = true)
    public static class Discard implements Callable<Integer> {

        private final InboundReviewStore review;
        private final Clock clock;

        @Parameters(index = "0", description = "The held message id.")
        private String id;

        public Discard(InboundReviewStore review, Clock clock) {
            this.review = review;
            this.clock = clock;
        }

        @Override
        public Integer call() {
            if (review.decide(id, false, clock.instant()).isEmpty()) {
                System.err.println("jclaw: no message held under '" + id + "' (already decided?)");
                return 1;
            }
            System.out.println("Discarded " + id + "; nothing was run.");
            return 0;
        }
    }
}
