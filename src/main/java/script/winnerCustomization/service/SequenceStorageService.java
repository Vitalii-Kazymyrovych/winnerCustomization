package script.winnerCustomization.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.SequenceRecord;

import java.sql.Timestamp;
import java.util.List;

@Service
public class SequenceStorageService {
    private static final Logger log = LoggerFactory.getLogger(SequenceStorageService.class);

    private final JdbcTemplate sequenceJdbc;
    private final RuntimeConfig runtimeConfig;

    public SequenceStorageService(@Qualifier("sequenceJdbc") JdbcTemplate sequenceJdbc,
                                  RuntimeConfig runtimeConfig) {
        this.sequenceJdbc = sequenceJdbc;
        this.runtimeConfig = runtimeConfig;
    }

    public void initialize() {
        log.info("Initializing sequence storage table vehicle_sequences if needed");
        try {
            sequenceJdbc.execute("""
                    create table if not exists vehicle_sequences (
                        id bigserial primary key,
                        plate_number varchar(32) not null,
                        started_at timestamp not null,
                        finished_at timestamp null,
                        path text not null,
                        stage_durations text null,
                        alerts text null
                    )
                    """);
        } catch (CannotGetJdbcConnectionException ex) {
            String host = runtimeConfig.get().getSequenceDatabase().getHost();
            int port = runtimeConfig.get().getSequenceDatabase().getPort();
            String db = runtimeConfig.get().getSequenceDatabase().getDb();
            String user = runtimeConfig.get().getSequenceDatabase().getUser();
            throw new IllegalStateException(
                    "Cannot connect to sequence database '%s' at %s:%d as user '%s'. " +
                            "Check that PostgreSQL is running, the database exists, and config.json sequenceDatabase values are correct."
                            .formatted(db, host, port, user),
                    ex
            );
        }
        log.info("Sequence storage initialization complete");
    }

    public void replaceAll(List<SequenceRecord> records) {
        log.info("Replacing sequence storage data with {} records", records.size());
        sequenceJdbc.update("delete from vehicle_sequences");
        for (SequenceRecord record : records) {
            log.debug("Persisting sequence for plate={} startedAt={} finishedAt={}",
                    record.getPlateNumber(), record.getStartedAt(), record.getFinishedAt());
            sequenceJdbc.update("""
                            insert into vehicle_sequences(plate_number, started_at, finished_at, path, stage_durations, alerts)
                            values (?, ?, ?, ?, ?, ?)
                            """,
                    record.getPlateNumber(),
                    Timestamp.valueOf(record.getStartedAt()),
                    record.getFinishedAt() == null ? null : Timestamp.valueOf(record.getFinishedAt()),
                    record.getPath(),
                    record.stageDurations(),
                    String.join(" | ", record.getAlerts())
            );
        }
        log.info("Sequence storage replacement finished");
    }
}
