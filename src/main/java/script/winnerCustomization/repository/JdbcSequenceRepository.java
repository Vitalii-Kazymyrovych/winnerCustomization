package script.winnerCustomization.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import script.winnerCustomization.model.SequenceRecord;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class JdbcSequenceRepository implements SequenceRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcSequenceRepository.class);

    private final JdbcTemplate sequenceJdbc;

    public JdbcSequenceRepository(@Qualifier("sequenceJdbc") JdbcTemplate sequenceJdbc) {
        this.sequenceJdbc = sequenceJdbc;
    }

    @Override
    public void initialize() {
        sequenceJdbc.execute("""
                create table if not exists sequence_records (
                    id bigserial primary key,
                    plate_number varchar(32) not null,
                    started_at timestamp not null,
                    finished_at timestamp null
                )
                """);
        sequenceJdbc.execute("""
                create table if not exists sequence_stages (
                    id bigserial primary key,
                    sequence_id bigint not null references sequence_records(id) on delete cascade,
                    stage_name varchar(128) not null,
                    stage_label varchar(128) not null,
                    stage_type varchar(32) not null,
                    partial boolean not null,
                    candidate boolean not null,
                    time_in timestamp null,
                    time_out timestamp null,
                    alerts text null
                )
                """);
    }

    @Override
    public List<SequenceRecord> findAll() {
        throw new UnsupportedOperationException("SequenceRepository.findAll is not used by the current workflow");
    }

    @Override
    public void replaceAll(List<SequenceRecord> entities) {
        log.info("Persisting {} sequence records", entities.size());
        sequenceJdbc.update("delete from sequence_stages");
        sequenceJdbc.update("delete from sequence_records");
        for (SequenceRecord record : entities) {
            Long sequenceId = sequenceJdbc.queryForObject("""
                    insert into sequence_records(plate_number, started_at, finished_at)
                    values (?, ?, ?) returning id
                    """, Long.class,
                    record.getPlateNumber(),
                    Timestamp.valueOf(record.getStartedAt()),
                    record.getFinishedAt() == null ? null : Timestamp.valueOf(record.getFinishedAt()));
            for (SequenceRecord.StageWindow stage : record.stagesChronologically()) {
                sequenceJdbc.update("""
                        insert into sequence_stages(sequence_id, stage_name, stage_label, stage_type, partial, candidate, time_in, time_out, alerts)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        sequenceId,
                        stage.stageName(),
                        stage.stageLabel(),
                        stage.stageType().name(),
                        stage.partial(),
                        stage.candidate(),
                        stage.timeIn() == null ? null : Timestamp.valueOf(stage.timeIn()),
                        stage.timeOut() == null ? null : Timestamp.valueOf(stage.timeOut()),
                        String.join(" | ", stage.alerts()));
            }
        }
    }
}
