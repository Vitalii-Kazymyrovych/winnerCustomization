package script.winnerCustomization.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

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
        String baseSql = "select id, plate_number, analytics_id, direction, created_at from " + schema + "." + table;
        LocalDateTime loadFrom = runtimeConfig.get().getSourceTable().getLoadFrom();
        String sql = loadFrom == null
                ? baseSql + " order by created_at asc, id asc"
                : baseSql + " where created_at >= ? order by created_at asc, id asc";
        log.info("Loading detections from source table {}.{} (from={})", schema, table, loadFrom);
        List<Detection> detections = loadFrom == null
                ? sourceJdbc.query(sql, (rs, rowNum) -> new Detection(
                rs.getLong("id"),
                rs.getString("plate_number"),
                rs.getInt("analytics_id"),
                (Integer) rs.getObject("direction"),
                rs.getTimestamp("created_at").toLocalDateTime()
        ))
                : sourceJdbc.query(sql, (rs, rowNum) -> new Detection(
                rs.getLong("id"),
                rs.getString("plate_number"),
                rs.getInt("analytics_id"),
                (Integer) rs.getObject("direction"),
                rs.getTimestamp("created_at").toLocalDateTime()
        ), Timestamp.valueOf(loadFrom));
        log.info("Loaded {} detections from source", detections.size());
        return detections;
    }
}
