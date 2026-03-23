package script.winnerCustomization.notifications;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class NotificationPlanner {

    public List<PendingNotification> collectDueNotifications(List<Detection> detections, AppConfig config, LocalDateTime now) {
        Map<String, PendingNotification> pending = new HashMap<>();
        List<Detection> ordered = detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();

        for (Detection detection : ordered) {
            for (AppConfig.NotificationRule rule : config.getNotifications()) {
                if (matches(rule, detection)) {
                    String key = alarmKey(detection.plateNumber(), rule.getCameraId());
                    pending.put(key, new PendingNotification(
                            0L,
                            detection.plateNumber(),
                            rule.getCameraId(),
                            detection.createdAt(),
                            detection.createdAt().plusSeconds(rule.getDelaySeconds()),
                            formatMessage(rule.getMessage(), detection.plateNumber())
                    ));
                }
            }
            pending.entrySet().removeIf(entry -> entry.getValue().plateNumber().equals(detection.plateNumber())
                    && entry.getValue().cameraId() != detection.analyticsId()
                    && !entry.getValue().triggerAt().isAfter(detection.createdAt()));
        }

        return pending.values().stream()
                .filter(notification -> !notification.dueAt().isAfter(now))
                .sorted(Comparator.comparing(PendingNotification::dueAt))
                .toList();
    }

    private boolean matches(AppConfig.NotificationRule rule, Detection detection) {
        if (!Objects.equals(rule.getCameraId(), detection.analyticsId())) {
            return false;
        }
        if (rule.getDirectionRange() == null || detection.direction() == null) {
            return true;
        }
        int direction = detection.direction();
        int from = rule.getDirectionRange().getFrom();
        int to = rule.getDirectionRange().getTo();
        return from < to ? direction >= from && direction < to : direction >= from || direction < to;
    }

    private String formatMessage(String template, String plate) {
        return template.contains("%s") ? template.formatted(plate) : template + ": " + plate;
    }

    private String alarmKey(String plate, int cameraId) {
        return plate + "::" + cameraId;
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
