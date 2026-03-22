package script.winnerCustomization.repository;

import script.winnerCustomization.service.NotificationService;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository {
    void initialize();
    void upsertPending(NotificationService.PendingNotification pendingNotification);
    void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt);
    List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit);
    List<NotificationService.PendingNotification> findAll();
    void markSent(long id, LocalDateTime sentAt);
}
