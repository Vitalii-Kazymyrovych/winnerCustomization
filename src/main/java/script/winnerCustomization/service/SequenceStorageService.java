package script.winnerCustomization.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.SequenceRecord;

import java.sql.Timestamp;
import java.util.List;

@Service
public class SequenceStorageService {
    private final JdbcTemplate sequenceJdbc;

    public SequenceStorageService(@Qualifier("sequenceJdbc") JdbcTemplate sequenceJdbc) {
        this.sequenceJdbc = sequenceJdbc;
    }

    public void initialize() {
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
    }

    public void replaceAll(List<SequenceRecord> records) {
        sequenceJdbc.update("delete from vehicle_sequences");
        for (SequenceRecord record : records) {
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
    }
}
