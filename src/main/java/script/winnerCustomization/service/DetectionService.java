package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

@Service
public class DetectionService {
    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    private final JdbcTemplate sourceJdbc;
    private final RuntimeConfig runtimeConfig;

    public DetectionService(@Qualifier("sourceJdbc") JdbcTemplate sourceJdbc, RuntimeConfig runtimeConfig) {
        this.sourceJdbc = sourceJdbc;
        this.runtimeConfig = runtimeConfig;
    }

    public List<Detection> loadAllDetections() {
        String schema = runtimeConfig.get().getSourceDatabase().getSchema();
        String table = runtimeConfig.get().getSourceTable().getTable();
        LocalDateTime loadFrom = runtimeConfig.get().getSourceTable().getLoadFrom();
        String sql = loadFrom == null
                ? buildBaseSql(schema, table) + " order by created_at asc, id asc"
                : buildBaseSql(schema, table) + " where created_at >= ? order by created_at asc, id asc";
        return queryDetections(
                schema,
                table,
                "from=%s".formatted(loadFrom),
                () -> loadFrom == null
                        ? sourceJdbc.query(sql, detectionRowMapper())
                        : sourceJdbc.query(sql, detectionRowMapper(), Timestamp.valueOf(loadFrom))
        );
    }

    public List<Detection> loadDetectionsBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        String schema = runtimeConfig.get().getSourceDatabase().getSchema();
        String table = runtimeConfig.get().getSourceTable().getTable();
        String sql = buildBaseSql(schema, table)
                + " where created_at >= ? and created_at < ? order by created_at asc, id asc";
        return queryDetections(
                schema,
                table,
                "from=%s, toExclusive=%s".formatted(fromInclusive, toExclusive),
                () -> sourceJdbc.query(sql,
                        detectionRowMapper(),
                        Timestamp.valueOf(fromInclusive),
                        Timestamp.valueOf(toExclusive))
        );
    }

    private List<Detection> queryDetections(String schema,
                                            String table,
                                            String filterDescription,
                                            Supplier<List<Detection>> query) {
        log.info("Loading detections from source table {}.{} ({})", schema, table, filterDescription);
        List<Detection> detections = query.get();
        log.info("Loaded {} detections from source", detections.size());
        return detections;
    }

    private RowMapper<Detection> detectionRowMapper() {
        return (rs, rowNum) -> new Detection(
                rs.getLong("id"),
                rs.getString("plate_number"),
                rs.getInt("analytics_id"),
                (Integer) rs.getObject("direction"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private String buildBaseSql(String schema, String table) {
        return "select id, plate_number, analytics_id, direction, created_at from " + schema + "." + table;
    }
}
