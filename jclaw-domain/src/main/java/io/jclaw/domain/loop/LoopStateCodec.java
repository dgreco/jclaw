// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.domain.loop;

/**
 * Serializes {@link LoopExecutionState} for checkpointing.
 *
 * <p>Declared here, in the domain, rather than beside the interpreter that uses it. The
 * implementation lives in a storage substrate, and the interpreter lives in the loop layer — if
 * the port were declared in either, the other would have to depend upward. Putting it in the pure
 * core, which both already depend on, keeps the dependency ladder intact.
 *
 * <p>The payload is opaque to every store that handles it: only this codec and the machine
 * understand the bytes, which is what lets the state schema evolve without touching persistence.
 */
public interface LoopStateCodec {

    /** Encodes state to a checkpoint payload. */
    byte[] encode(LoopExecutionState state);

    /**
     * Decodes a checkpoint payload.
     *
     * @throws IllegalArgumentException when {@code schemaVersion} is not one this codec reads.
     *                                  Misreading old state would resume a run into a subtly wrong
     *                                  position, so refusing is the only safe behaviour.
     */
    LoopExecutionState decode(byte[] payload, int schemaVersion);
}
