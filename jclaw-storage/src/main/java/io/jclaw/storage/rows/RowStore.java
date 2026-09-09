package io.jclaw.storage.rows;

import java.util.List;
import java.util.Map;

/**
 * An ordered, append-mostly sequence of flat JSON-shaped rows: the one storage primitive every
 * durable store is written against.
 *
 * <p>Each store (events, transcript, runs, checkpoints, approvals, results, memories, routines,
 * MCP servers, secrets) appends rows and replays them in order; retention rewrites a store whole.
 * Keeping that contract this small is what lets the same store classes run over a JSONL file on
 * one machine and over a SQL table in a hosted deployment without knowing which. The medium is
 * chosen once, in the application wiring, by {@code jclaw.storage}.
 */
public interface RowStore {

    /** Appends one row. Durable before this returns. */
    void append(Map<String, Object> record);

    /** Every row, oldest first. A row that cannot be decoded is skipped, never fatal. */
    List<Map<String, Object>> readAll();

    /** Replaces every row, atomically as far as the medium allows. Used by retention. */
    void rewrite(List<Map<String, Object>> records);

    /** Number of rows. */
    default int size() {
        return readAll().size();
    }
}
