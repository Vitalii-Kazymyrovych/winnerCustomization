package script.winnerCustomization.repository;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.config.DatabaseConfig;
import script.winnerCustomization.config.DbConnectionConfig;
import script.winnerCustomization.model.AlertRecord;
import script.winnerCustomization.model.PlateSequence;
import script.winnerCustomization.model.Stage;
 
import java.sql.Timestamp;
import java.util.List;
 
@Repository
public class TargetRepository {
 
    private static final Logger log = LoggerFactory.getLogger(TargetRepository.class);
    private final ConfigLoader configLoader;
    private JdbcTemplate jdbcTemplate;
    private String schema;
 
    public TargetRepository(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }
 
    public void initialize() {
        DatabaseConfig dbConfig = configLoader.getConfig().getDatabase();
        DbConnectionConfig seqConfig = dbConfig.getSequence();
 
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(dbConfig.getJdbcUrl(seqConfig.getDb()));
        ds.setUsername(seqConfig.getUser());
        ds.setPassword(seqConfig.getPassword());
        this.jdbcTemplate = new JdbcTemplate(ds);
        this.schema = seqConfig.getSchema();
 
        log.info("Target repository initialized: {}", dbConfig.getJdbcUrl(seqConfig.getDb()));
    }
 
    /**
     * Clear all data from target tables and rewrite from scratch.
     */
    public void rewriteAll(List<PlateSequence> allSequences) {
        log.info("Rewriting target DB with {} sequences...", allSequences.size());
 
        // Clear existing data
        jdbcTemplate.execute("DELETE FROM \"" + schema + "\".alerts");
        jdbcTemplate.execute("DELETE FROM \"" + schema + "\".stages");
        jdbcTemplate.execute("DELETE FROM \"" + schema + "\".sequences");
 
        long seqId = 1;
        long stageId = 1;
        long alertId = 1;
 
        for (PlateSequence seq : allSequences) {
            seq.setId(seqId);
 
            jdbcTemplate.update(
                    "INSERT INTO \"" + schema + "\".sequences (id, plate_number, active, start_time, close_time) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    seqId,
                    seq.getPlateNumber(),
                    seq.isActive(),
                    toTimestamp(seq.getStartTime()),
                    toTimestamp(seq.getCloseTime())
            );
 
            for (Stage stage : seq.getStages()) {
                stage.setId(stageId);
 
                jdbcTemplate.update(
                        "INSERT INTO \"" + schema + "\".stages " +
                                "(id, sequence_id, name, label, type, active, \"full\", candidate, timeout, " +
                                "in_time, out_time, duration_seconds, plate_number) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        stageId, seqId, stage.getName(), stage.getLabel(), stage.getType(),
                        stage.isActive(), stage.isFull(), stage.isCandidate(), stage.getTimeout(),
                        toTimestamp(stage.getInTime()), toTimestamp(stage.getOutTime()),
                        stage.getDurationSeconds(), stage.getPlateNumber()
                );
 
                for (AlertRecord alert : stage.getAlerts()) {
                    alert.setId(alertId);
 
                    jdbcTemplate.update(
                            "INSERT INTO \"" + schema + "\".alerts " +
                                    "(id, plate_number, message, timeout_seconds, active, analytics_id, stage_id) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                            alertId, alert.getPlateNumber(), alert.getMessage(),
                            alert.getTimeoutSeconds(), alert.isActive(),
                            alert.getTriggerAnalyticsId(), stageId
                    );
                    alertId++;
                }
                stageId++;
            }
            seqId++;
        }
 
        // Reset sequences
        jdbcTemplate.execute("SELECT setval('\"" + schema + "\".sequences_id_seq', " + seqId + ", false)");
        jdbcTemplate.execute("SELECT setval('\"" + schema + "\".stages_id_seq', " + stageId + ", false)");
        jdbcTemplate.execute("SELECT setval('\"" + schema + "\".alerts_id_seq', " + alertId + ", false)");
 
        log.info("Target DB rewrite complete: {} sequences, {} stages, {} alerts",
                seqId - 1, stageId - 1, alertId - 1);
    }
 
    private Timestamp toTimestamp(java.time.LocalDateTime dt) {
        return dt != null ? Timestamp.valueOf(dt) : null;
    }
}
