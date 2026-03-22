package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.repository.NotificationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final TelegramNotifier telegramNotifier;
    private final Clock clock;

    public NotificationService(NotificationRepository notificationRepository,
                               TelegramNotifier telegramNotifier,
                               Clock clock) {
        this.notificationRepository = notificationRepository;
        this.telegramNotifier = telegramNotifier;
        this.clock = clock;
    }

    public List<SequenceRecord.NotificationEvent> evaluate(List<Detection> detections, AppConfig config) {
        List<SequenceRecord.NotificationEvent> events = new ArrayList<>();
        Map<String, PendingNotification> pending = new HashMap<>();
        List<Detection> ordered = detections.stream()
                .sorted(java.util.Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();
        for (Detection detection : ordered) {
            LocalDateTime eventTime = detection.createdAt();
            flushDue(pending, eventTime, events);
            for (AppConfig.NotificationRule rule : safeRules(config)) {
                String key = key(detection.plateNumber(), rule.getCameraId());
                PendingNotification existing = pending.get(key);
                if (existing != null && detection.analyticsId() != rule.getCameraId()) {
                    pending.remove(key);
                    continue;
                }
                if (matches(rule, detection)) {
                    pending.put(key, new PendingNotification(0L,
                            detection.plateNumber(),
                            rule.getCameraId(),
                            eventTime,
                            eventTime.plusSeconds(rule.getDelaySeconds()),
                            rule.getMessage() + ": " + detection.plateNumber()));
                }
            }
        }
        flushDue(pending, LocalDateTime.MAX.minusYears(1), events);
        return events;
    }

    public void syncPendingNotifications(List<Detection> detections, AppConfig config) {
        notificationRepository.initialize();
        List<SequenceRecord.NotificationEvent> ignored = new ArrayList<>();
        Map<String, PendingNotification> pending = new HashMap<>();
        List<Detection> ordered = detections.stream()
                .sorted(java.util.Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();
        for (Detection detection : ordered) {
            LocalDateTime eventTime = detection.createdAt();
            for (PendingNotification due : new ArrayList<>(pending.values())) {
                if (!due.dueAt().isAfter(eventTime)) {
                    notificationRepository.upsertPending(due);
                    pending.remove(key(due.plateNumber(), due.cameraId()));
                }
            }
            for (AppConfig.NotificationRule rule : safeRules(config)) {
                String key = key(detection.plateNumber(), rule.getCameraId());
                PendingNotification existing = pending.get(key);
                if (existing != null && detection.analyticsId() != rule.getCameraId()) {
                    notificationRepository.cancel(existing.plateNumber(), existing.cameraId(), existing.triggerAt());
                    pending.remove(key);
                    continue;
                }
                if (matches(rule, detection)) {
                    PendingNotification notification = new PendingNotification(0L,
                            detection.plateNumber(),
                            rule.getCameraId(),
                            eventTime,
                            eventTime.plusSeconds(rule.getDelaySeconds()),
                            rule.getMessage() + ": " + detection.plateNumber());
                    notificationRepository.upsertPending(notification);
                    pending.put(key, notification);
                }
            }
        }
        flushDue(pending, LocalDateTime.now(clock).plusYears(10), ignored).forEach(notificationRepository::upsertPending);
    }

    public void dispatchDueNotifications(AppConfig config, int limit) {
        notificationRepository.initialize();
        LocalDateTime now = LocalDateTime.now(clock);
        for (PendingNotification pendingNotification : notificationRepository.findDuePending(now, limit)) {
            telegramNotifier.sendIfEnabled(config.getMessaging(), pendingNotification.message());
            notificationRepository.markSent(pendingNotification.id(), now);
        }
    }

    private List<PendingNotification> flushDue(Map<String, PendingNotification> pending,
                                               LocalDateTime boundary,
                                               List<SequenceRecord.NotificationEvent> events) {
        List<PendingNotification> due = new ArrayList<>();
        for (PendingNotification notification : new ArrayList<>(pending.values())) {
            if (!notification.dueAt().isAfter(boundary)) {
                events.add(new SequenceRecord.NotificationEvent(notification.dueAt(), notification.message()));
                pending.remove(key(notification.plateNumber(), notification.cameraId()));
                due.add(notification);
            }
        }
        return due;
    }

    private List<AppConfig.NotificationRule> safeRules(AppConfig config) {
        return config == null || config.getNotifications() == null ? List.of() : config.getNotifications();
    }

    private boolean matches(AppConfig.NotificationRule rule, Detection detection) {
        return Objects.equals(rule.getCameraId(), detection.analyticsId())
                && matchesDirection(rule.getDirectionRange(), detection.direction());
    }

    private boolean matchesDirection(AppConfig.DirectionRange range, Integer direction) {
        if (range == null || range.getFrom() == null || range.getTo() == null || direction == null) {
            return true;
        }
        int from = Math.floorMod(range.getFrom(), 360);
        int to = Math.floorMod(range.getTo(), 360);
        int value = Math.floorMod(direction, 360);
        if (from < to) {
            return value >= from && value < to;
        }
        return value >= from || value < to;
    }

    private String key(String plate, int cameraId) {
        return plate + "@" + cameraId;
    }

    public record PendingNotification(long id,
                                      String plateNumber,
                                      int cameraId,
                                      LocalDateTime triggerAt,
                                      LocalDateTime dueAt,
                                      String message) {
    }
}
