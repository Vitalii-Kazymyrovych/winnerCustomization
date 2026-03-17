package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.AlertJobRecord;
import script.winnerCustomization.model.AlertJobType;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertJobStorageService {
    private static final Logger log = LoggerFactory.getLogger(AlertJobStorageService.class);

    private final JdbcTemplate sequenceJdbc;

    public AlertJobStorageService(@Qualifier("sequenceJdbc") JdbcTemplate sequenceJdbc) {
        this.sequenceJdbc = sequenceJdbc;
    }

    public void initialize() {
        sequenceJdbc.execute("""
                create table if not exists alert_jobs (
                    id bigserial primary key,
                    plate_number varchar(32) not null,
                    alert_type varchar(64) not null,
                    trigger_at timestamp not null,
                    due_at timestamp not null,
                    message text not null,
                    status varchar(16) not null default 'PENDING',
                    sent_at timestamp null,
                    cancelled_at timestamp null,
                    created_at timestamp not null default now(),
                    updated_at timestamp not null default now(),
                    unique (plate_number, alert_type, trigger_at)
                )
                """);
        sequenceJdbc.execute("""
                create index if not exists idx_alert_jobs_due_pending
                on alert_jobs (due_at)
                where status = 'PENDING'
                """);
    }

    public void upsertPending(String plateNumber,
                              AlertJobType type,
                              LocalDateTime triggerAt,
                              LocalDateTime dueAt,
                              String message) {
        int affected = sequenceJdbc.update("""
                        insert into alert_jobs(plate_number, alert_type, trigger_at, due_at, message, status, sent_at, cancelled_at, updated_at)
                        values (?, ?, ?, ?, ?, 'PENDING', null, null, now())
                        on conflict (plate_number, alert_type, trigger_at)
                        do update set
                            due_at = excluded.due_at,
                            message = excluded.message,
                            status = 'PENDING',
                            sent_at = null,
                            cancelled_at = null,
                            updated_at = now()
                        """,
                plateNumber,
                type.name(),
                Timestamp.valueOf(triggerAt),
                Timestamp.valueOf(dueAt),
                message
        );
        log.debug("Upserted pending alert job plate={}, type={}, triggerAt={}, affected={}", plateNumber, type, triggerAt, affected);
    }

    public void cancel(String plateNumber, AlertJobType type, LocalDateTime triggerAt) {
        int affected = sequenceJdbc.update("""
                        update alert_jobs
                        set status = 'CANCELLED', cancelled_at = now(), updated_at = now()
                        where plate_number = ?
                          and alert_type = ?
                          and trigger_at = ?
                          and status = 'PENDING'
                        """,
                plateNumber,
                type.name(),
                Timestamp.valueOf(triggerAt)
        );
        if (affected > 0) {
            log.debug("Cancelled pending alert job plate={}, type={}, triggerAt={}", plateNumber, type, triggerAt);
        }
    }

    public List<AlertJobRecord> findDuePending(LocalDateTime now, int limit) {
        return sequenceJdbc.query("""
                        select id, plate_number, alert_type, trigger_at, due_at, message
                        from alert_jobs
                        where status = 'PENDING'
                          and due_at <= ?
                        order by due_at asc
                        limit ?
                        """,
                (rs, rowNum) -> new AlertJobRecord(
                        rs.getLong("id"),
                        rs.getString("plate_number"),
                        AlertJobType.valueOf(rs.getString("alert_type")),
                        rs.getTimestamp("trigger_at").toLocalDateTime(),
                        rs.getTimestamp("due_at").toLocalDateTime(),
                        rs.getString("message")
                ),
                Timestamp.valueOf(now),
                limit
        );
    }

    public void markSent(long id, LocalDateTime sentAt) {
        sequenceJdbc.update("""
                        update alert_jobs
                        set status = 'SENT', sent_at = ?, updated_at = now()
                        where id = ?
                          and status = 'PENDING'
                        """,
                Timestamp.valueOf(sentAt),
                id
        );
    }
}
