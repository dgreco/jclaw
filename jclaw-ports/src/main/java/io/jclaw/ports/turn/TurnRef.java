// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.ports.turn;

import java.util.Objects;

/**
 * Host-minted evidence that some durable record exists.
 *
 * <p>Refs are the only payload a loop driver may return in a {@link io.jclaw.ports.loop.LoopExit}.
 * The point is adversarial: a driver is userland code, so its claim that it finished is not
 * trusted. It must hand back a ref, and the exit applier re-reads the referenced record before
 * moving durable state. A syntactically valid ref is not evidence by itself — only a ref that
 * resolves to a real record is.
 *
 * <p>Consequently refs are minted <em>exclusively</em> by host ports and stores, never by a
 * driver, a product surface, or a model. Nothing in this file lets a caller fabricate one that
 * would survive validation, because validation is a store lookup rather than a format check.
 */
public sealed interface TurnRef extends Ident {

    /** A durable inbound user message accepted into the transcript. */
    record AcceptedMessageRef(String value) implements TurnRef {
        public AcceptedMessageRef {
            value = Ident.validate(value, "AcceptedMessageRef");
        }

        public static AcceptedMessageRef of(MessageId id) {
            return new AcceptedMessageRef(id.value());
        }
    }

    /** A durable assistant message (draft or final) written to the transcript. */
    record LoopMessageRef(String value) implements TurnRef {
        public LoopMessageRef {
            value = Ident.validate(value, "LoopMessageRef");
        }

        public static LoopMessageRef of(MessageId id) {
            return new LoopMessageRef(id.value());
        }
    }

    /** A durable capability result record. */
    record LoopResultRef(String value) implements TurnRef {
        public LoopResultRef {
            value = Ident.validate(value, "LoopResultRef");
        }
    }

    /** A raised approval/auth gate awaiting resolution. */
    record LoopGateRef(String value) implements TurnRef {
        public LoopGateRef {
            value = Ident.validate(value, "LoopGateRef");
        }

        public static LoopGateRef of(GateId id) {
            return new LoopGateRef(id.value());
        }
    }

    /** A persisted loop checkpoint payload. */
    record LoopCheckpointStateRef(String value) implements TurnRef {
        public LoopCheckpointStateRef {
            value = Ident.validate(value, "LoopCheckpointStateRef");
        }

        public static LoopCheckpointStateRef of(CheckpointId id) {
            return new LoopCheckpointStateRef(id.value());
        }
    }

    /** Short human-readable kind, used in audit lines and error categories. */
    default String kind() {
        return switch (this) {
            case AcceptedMessageRef ignored -> "accepted_message";
            case LoopMessageRef ignored -> "loop_message";
            case LoopResultRef ignored -> "loop_result";
            case LoopGateRef ignored -> "loop_gate";
            case LoopCheckpointStateRef ignored -> "loop_checkpoint";
        };
    }

    static <T extends TurnRef> T require(T ref, String what) {
        return Objects.requireNonNull(ref, what);
    }
}
