package script.winnerCustomization.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SequenceRecord {
    private final String plateNumber;
    private final LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private boolean closed;
    private final List<StageWindow> stages = new ArrayList<>();
    private final Set<NotificationEvent> notifications = new LinkedHashSet<>();

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
    public void addStage(StageWindow stage) { stages.add(stage); }
    public void addNotification(NotificationEvent event) { notifications.add(event); }
    public List<NotificationEvent> getNotifications() { return List.copyOf(notifications); }

    public List<StageWindow> stagesChronologically() {
        return stages.stream()
                .filter(StageWindow::visibleInReport)
                .sorted(Comparator.comparing(StageWindow::sortTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public enum StageType { REAL, TRANSITIONAL, SINGLE_CAMERA }

    public record StageWindow(
            String stageName,
            String stageLabel,
            StageType stageType,
            LocalDateTime timeIn,
            LocalDateTime timeOut,
            boolean partial,
            boolean candidate,
            boolean visibleInReport,
            List<String> alerts
    ) {
        public StageWindow(String stageName, String stageLabel, StageType stageType,
                           LocalDateTime timeIn, LocalDateTime timeOut,
                           boolean partial, boolean candidate, boolean visibleInReport) {
            this(stageName, stageLabel, stageType, timeIn, timeOut, partial, candidate, visibleInReport, List.of());
        }

        public LocalDateTime sortTime() {
            return timeIn != null ? timeIn : timeOut;
        }

        public String reportLabel() {
            return partial ? stageLabel + " (partial)" : stageLabel;
        }

        public Duration durationAt(LocalDateTime reportAt) {
            if (timeIn == null) {
                return null;
            }
            LocalDateTime effectiveEnd = timeOut != null ? timeOut : reportAt;
            return Duration.between(timeIn, effectiveEnd);
        }
    }

    public record NotificationEvent(String plateNumber, LocalDateTime triggerAt, String message) {
    }
}
