package io.jclaw.app.config;

import io.jclaw.storage.jsonl.JsonlFile;
import io.jclaw.storage.rows.RowStore;
import io.jclaw.storage.sql.JdbcRowStore;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where durable rows go: JSONL files under the state directory, or tables in a SQL database.
 *
 * <p>Every store bean is opened through this, so the choice is made once. The stores themselves
 * are written against {@link RowStore} and cannot tell the difference; the skill catalog (a
 * directory of markdown) and the thread locks (OS file locks) stay on the filesystem in both
 * modes.
 */
public final class StorageBackend {

    private final Optional<DataSource> dataSource;
    private final String description;

    private StorageBackend(Optional<DataSource> dataSource, String description) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.description = Objects.requireNonNull(description, "description");
    }

    public static StorageBackend jsonl(Path stateDir) {
        return new StorageBackend(Optional.empty(), "jsonl files under " + stateDir);
    }

    public static StorageBackend sql(DataSource dataSource, String displayUrl) {
        return new StorageBackend(Optional.of(dataSource), "sql at " + displayUrl);
    }

    /** Opens the named store: a table partition in SQL mode, else the JSONL file at {@code path}. */
    public RowStore open(String name, Path jsonlPath) {
        return dataSource.<RowStore>map(ds -> new JdbcRowStore(ds, name)).orElseGet(() -> new JsonlFile(jsonlPath));
    }

    public boolean isSql() {
        return dataSource.isPresent();
    }

    public Optional<DataSource> dataSource() {
        return dataSource;
    }

    /** For {@code doctor} and logs. Never includes credentials. */
    public String describe() {
        return description;
    }
}
