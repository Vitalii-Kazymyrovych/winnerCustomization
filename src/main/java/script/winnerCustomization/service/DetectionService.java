package script.winnerCustomization.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;

import java.util.List;

@Service
public class DetectionService {
    private final JdbcTemplate sourceJdbc;
    private final RuntimeConfig runtimeConfig;

    public DetectionService(@Qualifier("sourceJdbc") JdbcTemplate sourceJdbc, RuntimeConfig runtimeConfig) {
        this.sourceJdbc = sourceJdbc;
        this.runtimeConfig = runtimeConfig;
    }

    public List<Detection> loadAllDetections() {
        String schema = runtimeConfig.get().getSourceDatabase().getSchema();
        String table = runtimeConfig.get().getSourceTable().getTable();
        String sql = "select id, plate_number, analytics_id, direction, created_at from " + schema + "." + table + " order by created_at asc, id asc";
        return sourceJdbc.query(sql, (rs, rowNum) -> new Detection(
                rs.getLong("id"),
                rs.getString("plate_number"),
                rs.getInt("analytics_id"),
                (Integer) rs.getObject("direction"),
                rs.getTimestamp("created_at").toLocalDateTime()
        ));
    }
}
