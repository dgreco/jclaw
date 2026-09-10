// SPDX-FileCopyrightText: 2026 David Greco
// SPDX-License-Identifier: Apache-2.0

package io.jclaw.storage.sql;

import io.jclaw.storage.rows.RowStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One store's rows in SQL.
 *
 * <p>Which table depends on the store: the busy ones have their own since schema version 2,
 * everything else shares {@code jclaw_rows} and filters by store name. {@link SqlSchema} decides,
 * so the split is described in one place and this class only follows it. A dedicated table needs
 * no predicate at all, which is the point — the index it scans holds one store's rows.
 *
 * <p>Rows are the same flat maps the JSONL files hold, serialised the same way, so a store's
 * codec does not know which medium it is on. Two columns are lifted out of the body for
 * indexing: {@code run} and {@code thread}, when the row carries string fields of those names.
 * They are copies, never the source of truth; the body is decoded as a whole.
 *
 * <p>Ordering is the identity column, which the database assigns monotonically per insert. That
 * is what makes {@code readAll} reproduce append order under concurrent writers, where a file
 * would need a lock.
 */
public final class JdbcRowStore implements RowStore {

    private static final Pattern STORE_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String store;
    private final String table;
    private final boolean shared;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public JdbcRowStore(DataSource dataSource, String store) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.store = Objects.requireNonNull(store, "store");
        if (!STORE_NAME.matcher(store).matches()) {
            throw new IllegalArgumentException("not a store name: '" + store + "'");
        }
        // The table name comes from a static map keyed by a validated store name, never from the
        // caller, so no part of these statements is interpolated from anything a run supplies.
        this.table = SqlSchema.tableFor(store);
        this.shared = SqlSchema.isShared(store);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public String store() {
        return store;
    }

    /** The table this store's rows are in, for diagnostics. */
    public String table() {
        return table;
    }

    @Override
    public void append(Map<String, Object> record) {
        Objects.requireNonNull(record, "record");
        String body = mapper.writeValueAsString(record);
        if (shared) {
            jdbc.update("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)",
                    store, lifted(record, "run"), lifted(record, "thread"), body);
        } else {
            jdbc.update("INSERT INTO " + table + " (run, thread, body) VALUES (?, ?, ?)",
                    lifted(record, "run"), lifted(record, "thread"), body);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readAll() {
        List<Map<String, Object>> rows = new ArrayList<>();
        org.springframework.jdbc.core.RowCallbackHandler handler = rs -> {
            try {
                rows.add(mapper.readValue(rs.getString(1), Map.class));
            } catch (RuntimeException e) {
                // As with a damaged JSONL line: one bad row costs that row, not the store.
            }
        };
        if (shared) {
            jdbc.query("SELECT body FROM jclaw_rows WHERE store = ? ORDER BY seq", handler, store);
        } else {
            jdbc.query("SELECT body FROM " + table + " ORDER BY seq", handler);
        }
        return rows;
    }

    @Override
    public void rewrite(List<Map<String, Object>> records) {
        Objects.requireNonNull(records, "records");
        List<Object[]> batch = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            String body = mapper.writeValueAsString(record);
            batch.add(shared
                    ? new Object[] {store, lifted(record, "run"), lifted(record, "thread"), body}
                    : new Object[] {lifted(record, "run"), lifted(record, "thread"), body});
        }
        tx.executeWithoutResult(status -> {
            if (shared) {
                jdbc.update("DELETE FROM jclaw_rows WHERE store = ?", store);
                if (!batch.isEmpty()) {
                    jdbc.batchUpdate("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)", batch);
                }
            } else {
                jdbc.update("DELETE FROM " + table);
                if (!batch.isEmpty()) {
                    jdbc.batchUpdate("INSERT INTO " + table + " (run, thread, body) VALUES (?, ?, ?)", batch);
                }
            }
        });
    }

    @Override
    public int size() {
        Integer count = shared
                ? jdbc.queryForObject("SELECT COUNT(*) FROM jclaw_rows WHERE store = ?", Integer.class, store)
                : jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static String lifted(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (value instanceof String s && s.length() <= 200) {
            return s;
        }
        return null;
    }
}
