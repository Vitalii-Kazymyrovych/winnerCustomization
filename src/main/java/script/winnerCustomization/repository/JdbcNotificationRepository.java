package script.winnerCustomization.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import script.winnerCustomization.service.NotificationService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private final JdbcTemplate sequenceJdbc;

    public JdbcNotificationRepository(@Qualifier("sequenceJdbc") JdbcTemplate sequenceJdbc) {
        this.sequenceJdbc = sequenceJdbc;
    }

    @Override
    public void initialize() {
        sequenceJdbc.execute("""
                create table if not exists pending_notifications (
                    id bigserial primary key,
                    plate_number varchar(32) not null,
                    camera_id integer not null,
                    trigger_at timestamp not null,
                    due_at timestamp not null,
                    message text not null,
                    status varchar(16) not null default 'PENDING',
                    sent_at timestamp null,
                    unique(plate_number, camera_id, trigger_at)
                )
                """);
    }

    @Override
    public void upsertPending(NotificationService.PendingNotification pendingNotification) {
        sequenceJdbc.update("""
                insert into pending_notifications(plate_number, camera_id, trigger_at, due_at, message, status)
                values (?, ?, ?, ?, ?, 'PENDING')
                on conflict (plate_number, camera_id, trigger_at)
                do update set due_at = excluded.due_at, message = excluded.message, status = 'PENDING', sent_at = null
                """,
                pendingNotification.plateNumber(),
                pendingNotification.cameraId(),
                Timestamp.valueOf(pendingNotification.triggerAt()),
                Timestamp.valueOf(pendingNotification.dueAt()),
                pendingNotification.message());
    }

    @Override
    public void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt) {
        sequenceJdbc.update("delete from pending_notifications where plate_number = ? and camera_id = ? and trigger_at = ? and status = 'PENDING'",
                plateNumber, cameraId, Timestamp.valueOf(triggerAt));
    }

    @Override
    public List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit) {
        return sequenceJdbc.query("""
                select id, plate_number, camera_id, trigger_at, due_at, message
                from pending_notifications
                where status = 'PENDING' and due_at <= ?
                order by due_at asc
                limit ?
                """,
                (rs, rowNum) -> new NotificationService.PendingNotification(
                        rs.getLong("id"),
                        rs.getString("plate_number"),
                        rs.getInt("camera_id"),
                        rs.getTimestamp("trigger_at").toLocalDateTime(),
                        rs.getTimestamp("due_at").toLocalDateTime(),
                        rs.getString("message")
                ),
                Timestamp.valueOf(now), limit);
    }

    @Override
    public List<NotificationService.PendingNotification> findAll() {
        return sequenceJdbc.query("select id, plate_number, camera_id, trigger_at, due_at, message from pending_notifications order by due_at asc",
                (rs, rowNum) -> new NotificationService.PendingNotification(
                        rs.getLong("id"),
                        rs.getString("plate_number"),
                        rs.getInt("camera_id"),
                        rs.getTimestamp("trigger_at").toLocalDateTime(),
                        rs.getTimestamp("due_at").toLocalDateTime(),
                        rs.getString("message")
                ));
    }

    @Override
    public void markSent(long id, LocalDateTime sentAt) {
        sequenceJdbc.update("update pending_notifications set status = 'SENT', sent_at = ? where id = ?", Timestamp.valueOf(sentAt), id);
    }
}
