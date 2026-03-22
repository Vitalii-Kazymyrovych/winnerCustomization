package script.winnerCustomization.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class SequenceRecord {
    private final String plateNumber;
    private final LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private boolean closed;
    private final List<StageWindow> stages = new ArrayList<>();
    private final List<NotificationEvent> notifications = new ArrayList<>();

    public SequenceRecord(String plateNumber, LocalDateTime startedAt) {
        this.plateNumber = plateNumber;
        this.startedAt = startedAt;
    }

    public String getPlateNumber() { return plateNumber; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }
    public List<StageWindow> getStages() { return stages; }
    public List<NotificationEvent> getNotifications() { return notifications; }

    public void addStage(StageWindow stage) {
        stages.add(stage);
    }

    public void addNotification(NotificationEvent notificationEvent) {
        boolean exists = notifications.stream()
                .anyMatch(existing -> Objects.equals(existing.plateNumber(), notificationEvent.plateNumber())
                        && Objects.equals(existing.triggeredAt(), notificationEvent.triggeredAt())
                        && Objects.equals(existing.message(), notificationEvent.message()));
        if (!exists) {
            notifications.add(notificationEvent);
        }
    }

    public List<StageWindow> stagesChronologically() {
        return stages.stream()
                .filter(StageWindow::showInReport)
                .sorted(Comparator
                        .comparing(StageWindow::sortTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StageWindow::stageLabel))
                .toList();
    }

    public record NotificationEvent(String plateNumber, LocalDateTime triggeredAt, String message) {}

    public enum StageType {
        REAL,
        TRANSITIONAL,
        SINGLE_CAMERA
    }

    public static final class StageWindow {
        private final String stageName;
        private final String stageLabel;
        private final StageType stageType;
        private LocalDateTime timeIn;
        private LocalDateTime timeOut;
        private LocalDateTime lastSeenAt;
        private final boolean partial;
        private final boolean candidate;
        private final boolean showInReport;
        private final List<String> alerts = new ArrayList<>();

        public StageWindow(String stageName,
                           String stageLabel,
                           StageType stageType,
                           LocalDateTime timeIn,
                           LocalDateTime timeOut,
                           boolean partial,
                           boolean candidate,
                           boolean showInReport) {
            this.stageName = stageName;
            this.stageLabel = stageLabel;
            this.stageType = stageType;
            this.timeIn = timeIn;
            this.timeOut = timeOut;
            this.lastSeenAt = timeOut != null ? timeOut : timeIn;
            this.partial = partial;
            this.candidate = candidate;
            this.showInReport = showInReport;
        }

        public String stageName() { return stageName; }
        public String stageLabel() { return stageLabel; }
        public StageType stageType() { return stageType; }
        public LocalDateTime timeIn() { return timeIn; }
        public LocalDateTime timeOut() { return timeOut; }
        public LocalDateTime lastSeenAt() { return lastSeenAt; }
        public boolean partial() { return partial; }
        public boolean candidate() { return candidate; }
        public boolean showInReport() { return showInReport; }
        public List<String> alerts() { return alerts; }
        public void setTimeIn(LocalDateTime timeIn) { this.timeIn = timeIn; }
        public void setTimeOut(LocalDateTime timeOut) {
            this.timeOut = timeOut;
            if (timeOut != null) {
                this.lastSeenAt = timeOut;
            }
        }
        public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
        public void addAlert(String alert) {
            if (alert != null && !alert.isBlank() && !alerts.contains(alert)) {
                alerts.add(alert);
            }
        }
        public LocalDateTime sortTime() { return timeIn != null ? timeIn : timeOut; }
        public boolean overlaps(LocalDateTime eventTime) {
            if (eventTime == null) {
                return false;
            }
            if (timeIn == null) {
                return Objects.equals(timeOut, eventTime);
            }
            if (timeOut == null) {
                return !eventTime.isBefore(timeIn);
            }
            return !eventTime.isBefore(timeIn) && !eventTime.isAfter(timeOut);
        }
        public String durationText(LocalDateTime reportTime) {
            LocalDateTime end = timeOut != null ? timeOut : reportTime;
            if (timeIn == null || end == null) {
                return "";
            }
            Duration duration = Duration.between(timeIn, end);
            long seconds = Math.max(0, duration.toSeconds());
            return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
    }
}
