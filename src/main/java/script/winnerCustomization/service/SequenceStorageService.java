package script.winnerCustomization.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.SequenceRecord;

import java.sql.Timestamp;
import java.util.List;

@Service
public class SequenceStorageService {
    private static final Logger log = LoggerFactory.getLogger(SequenceStorageService.class);

    private final JdbcTemplate sequenceJdbc;

    public SequenceStorageService(@Qualifier("sequenceJdbc") JdbcTemplate sequenceJdbc) {
        this.sequenceJdbc = sequenceJdbc;
    }

    public void initialize() {
        log.info("Initializing sequence storage table vehicle_sequences if needed");
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
