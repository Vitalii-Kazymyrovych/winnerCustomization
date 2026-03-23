package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.notifications.NotificationPlanner;
import script.winnerCustomization.notifications.NotificationSender;
import script.winnerCustomization.repository.DetectionRepository;
import script.winnerCustomization.repository.NotificationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {
    private final DetectionRepository detectionRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPlanner notificationPlanner;
    private final NotificationSender notificationSender;
    private final RuntimeConfig runtimeConfig;
    private final Clock clock;

    public NotificationService(DetectionRepository detectionRepository,
                               NotificationRepository notificationRepository,
                               NotificationPlanner notificationPlanner,
                               NotificationSender notificationSender,
                               RuntimeConfig runtimeConfig,
                               Clock clock) {
        this.detectionRepository = detectionRepository;
        this.notificationRepository = notificationRepository;
        this.notificationPlanner = notificationPlanner;
        this.notificationSender = notificationSender;
        this.runtimeConfig = runtimeConfig;
        this.clock = clock;
    }

    public List<PendingNotification> refreshPendingNotifications() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Detection> detections = detectionRepository.findAll();
        List<NotificationPlanner.PendingNotification> due = notificationPlanner.collectDueNotifications(detections, runtimeConfig.get(), now);
        for (NotificationPlanner.PendingNotification notification : due) {
            PendingNotification pending = new PendingNotification(notification.id(), notification.plateNumber(), notification.cameraId(), notification.triggerAt(), notification.dueAt(), notification.message());
            notificationRepository.upsertPending(pending);
        }
        return notificationRepository.findDuePending(now, 100);
    }

    public void dispatchDueNotifications() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (PendingNotification pending : notificationRepository.findDuePending(now, 100)) {
            notificationSender.send(pending.message());
            notificationRepository.markSent(pending.id(), now);
        }
    }

    public record PendingNotification(
            long id,
            String plateNumber,
            int cameraId,
            LocalDateTime triggerAt,
            LocalDateTime dueAt,
            String message
    ) {
    }
}
