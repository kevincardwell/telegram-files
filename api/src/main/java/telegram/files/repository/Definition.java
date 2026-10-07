package telegram.files.repository;

import cn.hutool.core.lang.Version;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

import java.util.List;
import java.util.TreeMap;

public interface Definition {

    Log log = LogFactory.get();

    String getScheme();

    default TreeMap<Version, String[]> getMigrations() {
        return new TreeMap<>();
    }

    /**
     * Index DDL run on every startup. Must be idempotent (IF NOT EXISTS); failures are logged and ignored
     * so MySQL, which has no IF NOT EXISTS for indexes, just reports "duplicate key name".
     */
    default List<String> getIndexes() {
        return List.of();
    }

    default Future<Void> createTable(SqlClient sqlClient) {
        return sqlClient
                .query(getScheme())
                .execute()
                .onFailure(err -> log.error("Failed to create table: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    /**
     * Runs after migrations, which may add the indexed columns.
     */
    default Future<Void> createIndexes(SqlClient sqlClient) {
        Future<Void> future = Future.succeededFuture();
        for (String sql : getIndexes()) {
            future = future.compose(_ -> sqlClient.query(sql).execute()
                    .<Void>mapEmpty()
                    .recover(err -> {
                        log.debug("Index statement skipped: %s (%s)".formatted(sql, err.getMessage()));
                        return Future.succeededFuture();
                    }));
        }
        return future;
    }

    default Future<Void> migrate(SqlClient sqlClient, Version lastVersion, Version currentVersion) {
        TreeMap<Version, String[]> migrations = getMigrations();
        // A database written by a newer build (e.g. upstream 0.4.0) has nothing to migrate.
        if (migrations.isEmpty() || lastVersion.compareTo(currentVersion) >= 0) {
            return Future.succeededFuture();
        }
        // Statements run one at a time, in version order: ALTERs racing each other lock SQLite.
        Future<Void> future = Future.succeededFuture();
        for (String[] statements : migrations.subMap(lastVersion, false, currentVersion, true).values()) {
            for (String sql : statements) {
                future = future.compose(_ -> sqlClient.query(sql).execute()
                        .<Void>mapEmpty()
                        .recover(err -> {
                            log.error("Failed to apply migration: %s (%s)".formatted(sql, err.getMessage()));
                            return Future.succeededFuture();
                        }));
            }
        }
        return future;
    }
}
