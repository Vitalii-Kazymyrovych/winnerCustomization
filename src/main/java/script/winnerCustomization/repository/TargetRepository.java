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

    // Next available IDs — updated after rewriteAll so updateActive can assign new IDs without colliding
    private long nextSeqId = 1;
    private long nextStageId = 1;
    private long nextAlertId = 1;

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
     * Used on startup to build a complete, consistent view from all historical detections.
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

        // Track next available IDs for subsequent updateActive calls
        nextSeqId = seqId;
        nextStageId = stageId;
        nextAlertId = alertId;

        log.info("Target DB rewrite complete: {} sequences, {} stages, {} alerts",
                seqId - 1, stageId - 1, alertId - 1);
    }

    /**
     * Incremental update during polling: only writes active sequences, their active stages
     * (including transitional candidates with timeout > 0), and their active alerts.
     * Closed sequences, inactive stages, and inactive alerts are not touched.
     *
     * <p>This works by deleting only the active rows from the DB and reinserting the current
     * in-memory active state. Rows with active=false (written at startup or in a prior cycle)
     * remain untouched in the DB.
     */
    public void updateActive(List<PlateSequence> activeSequences, List<PlateSequence> newlyClosedSequences) {
        log.debug("Updating active DB state: {} active, {} newly closed",
                activeSequences.size(), newlyClosedSequences.size());

        // Remove currently-active rows only; closed sequences (active=false) are untouched
        jdbcTemplate.execute("DELETE FROM \"" + schema + "\".alerts WHERE active = true");
        jdbcTemplate.execute("DELETE FROM \"" + schema + "\".stages WHERE active = true");
        jdbcTemplate.execute("DELETE FROM \"" + schema + "\".sequences WHERE active = true");

        for (PlateSequence seq : activeSequences) {
            if (seq.getId() == 0) {
                seq.setId(nextSeqId++);
            }

            jdbcTemplate.update(
                    "INSERT INTO \"" + schema + "\".sequences (id, plate_number, active, start_time, close_time) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    seq.getId(), seq.getPlateNumber(), seq.isActive(),
                    toTimestamp(seq.getStartTime()), toTimestamp(seq.getCloseTime())
            );

            for (Stage stage : seq.getStages()) {
                // Only write active stages; candidates are active=true so they are included here.
                // Inactive stages (active=false) already in DB from startup are not touched.
                if (!stage.isActive()) {
                    continue;
                }

                if (stage.getId() == 0) {
                    stage.setId(nextStageId++);
                }

                jdbcTemplate.update(
                        "INSERT INTO \"" + schema + "\".stages " +
                                "(id, sequence_id, name, label, type, active, \"full\", candidate, timeout, " +
                                "in_time, out_time, duration_seconds, plate_number) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        stage.getId(), seq.getId(), stage.getName(), stage.getLabel(), stage.getType(),
                        stage.isActive(), stage.isFull(), stage.isCandidate(), stage.getTimeout(),
                        toTimestamp(stage.getInTime()), toTimestamp(stage.getOutTime()),
                        stage.getDurationSeconds(), stage.getPlateNumber()
                );

                for (AlertRecord alert : stage.getAlerts()) {
                    // Only write active alerts; fired/deactivated alerts are not touched.
                    if (!alert.isActive()) {
                        continue;
                    }

                    if (alert.getId() == 0) {
                        alert.setId(nextAlertId++);
                    }

                    jdbcTemplate.update(
                            "INSERT INTO \"" + schema + "\".alerts " +
                                    "(id, plate_number, message, timeout_seconds, active, analytics_id, stage_id) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                            alert.getId(), alert.getPlateNumber(), alert.getMessage(),
                            alert.getTimeoutSeconds(), alert.isActive(),
                            alert.getTriggerAnalyticsId(), stage.getId()
                    );
                }
            }
        }

        // Write sequences that closed during this poll cycle (active=false).
        // These were deleted from the DB by the DELETE above (they had active=true) and must be
        // reinserted as closed so they are not lost.
        for (PlateSequence seq : newlyClosedSequences) {
            if (seq.getId() == 0) {
                seq.setId(nextSeqId++);
            }

            jdbcTemplate.update(
                    "INSERT INTO \"" + schema + "\".sequences (id, plate_number, active, start_time, close_time) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    seq.getId(), seq.getPlateNumber(), seq.isActive(),
                    toTimestamp(seq.getStartTime()), toTimestamp(seq.getCloseTime())
            );

            // Write ALL stages of the closed sequence regardless of active flag —
            // runtime-created sequences may have stages that were never written to the DB.
            for (Stage stage : seq.getStages()) {
                if (stage.getId() == 0) {
                    stage.setId(nextStageId++);
                }

                jdbcTemplate.update(
                        "INSERT INTO \"" + schema + "\".stages " +
                                "(id, sequence_id, name, label, type, active, \"full\", candidate, timeout, " +
                                "in_time, out_time, duration_seconds, plate_number) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        stage.getId(), seq.getId(), stage.getName(), stage.getLabel(), stage.getType(),
                        stage.isActive(), stage.isFull(), stage.isCandidate(), stage.getTimeout(),
                        toTimestamp(stage.getInTime()), toTimestamp(stage.getOutTime()),
                        stage.getDurationSeconds(), stage.getPlateNumber()
                );

                for (AlertRecord alert : stage.getAlerts()) {
                    if (alert.getId() == 0) {
                        alert.setId(nextAlertId++);
                    }

                    jdbcTemplate.update(
                            "INSERT INTO \"" + schema + "\".alerts " +
                                    "(id, plate_number, message, timeout_seconds, active, analytics_id, stage_id) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                            alert.getId(), alert.getPlateNumber(), alert.getMessage(),
                            alert.getTimeoutSeconds(), alert.isActive(),
                            alert.getTriggerAnalyticsId(), stage.getId()
                    );
                }
            }
        }

        log.debug("Active DB update complete: {} active, {} newly closed written",
                activeSequences.size(), newlyClosedSequences.size());
    }

    private Timestamp toTimestamp(java.time.LocalDateTime dt) {
        return dt != null ? Timestamp.valueOf(dt) : null;
    }
}
