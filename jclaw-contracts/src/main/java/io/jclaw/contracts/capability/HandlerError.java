package io.jclaw.contracts.capability;

import java.util.Objects;

/**
 * Why a runtime lane did not produce a result.
 *
 * <p>The distinction is not cosmetic. "Policy refused this" and "the code broke" are different
 * events with different responses: the first is the security boundary working — and is exactly
 * what an operator greps the audit log for after an incident — while the second is a defect to
 * fix. Collapsing both into a generic failure means a blocked workspace escape is indistinguishable
 * from a transient I/O error, and attacks hide in the noise.
 *
 * <p>Making it a sealed type rather than a convention means a handler cannot accidentally report a
 * denial as a failure; the compiler asks which one it is.
 */
public sealed interface HandlerError {

    /**
     * A guard or policy refused the request. The boundary did its job.
     *
     * @param reason stable category, e.g. {@code path_outside_workspace}, {@code host_denied}
     */
    record Denied(String reason) implements HandlerError {
        public Denied {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The lane was permitted to run and could not complete.
     *
     * @param category stable category, e.g. {@code read_failed}, {@code timed_out}
     */
    record Failed(String category) implements HandlerError {
        public Failed {
            Objects.requireNonNull(category, "category");
        }
    }

    static HandlerError denied(String reason) {
        return new Denied(reason);
    }

    static HandlerError failed(String category) {
        return new Failed(category);
    }

    /** The stable token recorded in events and shown to the model. */
    default String token() {
        return switch (this) {
            case Denied denied -> denied.reason();
            case Failed failed -> failed.category();
        };
    }
}
