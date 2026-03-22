package script.winnerCustomization.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

@Repository
public class JdbcDetectionRepository implements DetectionRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcDetectionRepository.class);

    private final JdbcTemplate sourceJdbc;
    private final RuntimeConfig runtimeConfig;

    public JdbcDetectionRepository(@Qualifier("sourceJdbc") JdbcTemplate sourceJdbc, RuntimeConfig runtimeConfig) {
        this.sourceJdbc = sourceJdbc;
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    public List<Detection> findAll() {
        String schema = runtimeConfig.get().getSourceDatabase().getSchema();
        String table = runtimeConfig.get().getSourceTable().getTable();
        LocalDateTime loadFrom = runtimeConfig.get().getSourceTable().getLoadFrom();
        String sql = loadFrom == null
                ? baseSql(schema, table) + " order by created_at asc, id asc"
                : baseSql(schema, table) + " where created_at >= ? order by created_at asc, id asc";
        return execute(schema, table, () -> loadFrom == null
                ? sourceJdbc.query(sql, mapper())
                : sourceJdbc.query(sql, mapper(), Timestamp.valueOf(loadFrom)));
    }

    @Override
    public List<Detection> findBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        String schema = runtimeConfig.get().getSourceDatabase().getSchema();
        String table = runtimeConfig.get().getSourceTable().getTable();
        String sql = baseSql(schema, table) + " where created_at >= ? and created_at < ? order by created_at asc, id asc";
        return execute(schema, table, () -> sourceJdbc.query(sql, mapper(), Timestamp.valueOf(fromInclusive), Timestamp.valueOf(toExclusive)));
    }

    private List<Detection> execute(String schema, String table, Supplier<List<Detection>> supplier) {
        log.info("Loading detections from {}.{}", schema, table);
        List<Detection> detections = supplier.get();
        log.info("Loaded {} detections", detections.size());
        return detections;
    }

    private RowMapper<Detection> mapper() {
        return (rs, rowNum) -> new Detection(
                rs.getLong("id"),
                rs.getString("plate_number"),
                rs.getInt("analytics_id"),
                (Integer) rs.getObject("direction"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String baseSql(String schema, String table) {
        return "select id, plate_number, analytics_id, direction, created_at from " + schema + "." + table;
    }
}
