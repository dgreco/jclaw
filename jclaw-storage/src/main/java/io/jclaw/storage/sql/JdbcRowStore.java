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
 * One store's rows in the shared {@code jclaw_rows} table.
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
    private final JsonMapper mapper = JsonMapper.builder().build();

    public JdbcRowStore(DataSource dataSource, String store) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.store = Objects.requireNonNull(store, "store");
        if (!STORE_NAME.matcher(store).matches()) {
            throw new IllegalArgumentException("not a store name: '" + store + "'");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public String store() {
        return store;
    }

    @Override
    public void append(Map<String, Object> record) {
        Objects.requireNonNull(record, "record");
        String body = mapper.writeValueAsString(record);
        jdbc.update("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)",
                store, lifted(record, "run"), lifted(record, "thread"), body);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readAll() {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query("SELECT body FROM jclaw_rows WHERE store = ? ORDER BY seq", rs -> {
            try {
                rows.add(mapper.readValue(rs.getString(1), Map.class));
            } catch (RuntimeException e) {
                // As with a damaged JSONL line: one bad row costs that row, not the store.
            }
        }, store);
        return rows;
    }

    @Override
    public void rewrite(List<Map<String, Object>> records) {
        Objects.requireNonNull(records, "records");
        List<Object[]> batch = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            batch.add(new Object[] {store, lifted(record, "run"), lifted(record, "thread"),
                    mapper.writeValueAsString(record)});
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM jclaw_rows WHERE store = ?", store);
            if (!batch.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO jclaw_rows (store, run, thread, body) VALUES (?, ?, ?, ?)", batch);
            }
        });
    }

    @Override
    public int size() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM jclaw_rows WHERE store = ?", Integer.class, store);
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
