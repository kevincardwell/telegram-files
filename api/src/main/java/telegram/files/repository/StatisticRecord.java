package telegram.files.repository;

import cn.hutool.core.map.MapUtil;
import io.vertx.sqlclient.templates.RowMapper;
import io.vertx.sqlclient.templates.TupleMapper;

public record StatisticRecord(
        String relatedId,
        Type type,
        long timestamp,
        String data) {

    public enum Type {
        speed,

        ;
    }

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS statistic_record
            (
                related_id  VARCHAR(255),
                type        VARCHAR(255),
                timestamp   BIGINT,
                data        TEXT
            )
            """;

    public static class StatisticRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public java.util.List<String> getIndexes() {
            return java.util.List.of("CREATE INDEX %sidx_statistic_related_type_time ON statistic_record (related_id, type, timestamp)"
                    .formatted(telegram.files.Config.isMysql() ? "" : "IF NOT EXISTS "));
        }
    }

    public static RowMapper<StatisticRecord> ROW_MAPPER = row ->
            new StatisticRecord(row.getString("related_id"),
                    Type.valueOf(row.getString("type")),
                    row.getLong("timestamp"),
                    row.getString("data")
            );

    public static TupleMapper<StatisticRecord> PARAM_MAPPER = TupleMapper.mapper(r ->
            MapUtil.ofEntries(MapUtil.entry("related_id", r.relatedId),
                    MapUtil.entry("type", r.type().name()),
                    MapUtil.entry("timestamp", r.timestamp()),
                    MapUtil.entry("data", r.data())
            ));
}
