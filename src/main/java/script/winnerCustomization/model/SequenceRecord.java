package script.winnerCustomization.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public class SequenceRecord {
    private final String plateNumber;
    private final LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private final List<StageWindow> stages = new ArrayList<>();
    private final List<String> pathSteps = new ArrayList<>();

    public SequenceRecord(String plateNumber, LocalDateTime startedAt) {
        this.plateNumber = plateNumber;
        this.startedAt = startedAt;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public List<StageWindow> getStages() {
        return stages;
    }

    public List<StageWindow> stagesChronologically() {
        return stages.stream()
                .sorted(Comparator
                        .comparing(StageWindow::sortTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StageWindow::eventOrder))
                .toList();
    }

    public void addStage(StageWindow stage) {
        stages.add(stage);
    }

    public void addPathStep(String step) {
        if (step != null && !step.isBlank()) {
            pathSteps.add(step);
        }
    }

    public String getPath() {
        if (pathSteps.isEmpty()) {
            return "";
        }
        return String.join(" -> ", pathSteps);
    }

    public List<String> getAlerts() {
        List<String> alerts = new ArrayList<>();
        for (StageWindow stage : stagesChronologically()) {
            if (stage.alert() != null && !stage.alert().isBlank()) {
                alerts.add(stage.alert());
            }
        }
        return alerts;
    }

    public String stageDurations() {
        StringJoiner joiner = new StringJoiner("; ");
        for (StageWindow stage : stagesChronologically()) {
            if (stage.timeIn() == null || stage.timeOut() == null) {
                continue;
            }
            joiner.add(stage.reportLabel() + "=" + Duration.between(stage.timeIn(), stage.timeOut()).toMinutes() + "m");
        }
        return joiner.toString();
    }

    public List<StageWindow> getStagesByType(StageType type) {
        return stagesChronologically().stream()
                .filter(stage -> stage.stageType() == type)
                .toList();
    }

    public StageWindow findLatestStage(StageType type) {
        return stagesChronologically().stream()
                .filter(stage -> stage.stageType() == type)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public record StageWindow(StageType stageType,
                              LocalDateTime timeIn,
                              LocalDateTime timeOut,
                              String reportLabelOverride,
                              String alert,
                              boolean partial,
                              int eventOrder) {
        public String reportLabel() {
            return reportLabelOverride != null && !reportLabelOverride.isBlank()
                    ? reportLabelOverride
                    : stageType.reportLabel();
        }

        public LocalDateTime sortTime() {
            return timeIn != null ? timeIn : timeOut;
        }

        public StageWindow withTimeIn(LocalDateTime value) {
            return new StageWindow(stageType, value, timeOut, reportLabelOverride, alert, partial, eventOrder);
        }

        public StageWindow withTimeOut(LocalDateTime value) {
            return new StageWindow(stageType, timeIn, value, reportLabelOverride, alert, partial, eventOrder);
        }

        public StageWindow withAlert(String value) {
            return new StageWindow(stageType, timeIn, timeOut, reportLabelOverride, value, partial, eventOrder);
        }

        public StageWindow asPartial(boolean value) {
            return new StageWindow(stageType, timeIn, timeOut, reportLabelOverride, alert, value, eventOrder);
        }

        public boolean sameBounds(StageWindow other) {
            return other != null
                    && stageType == other.stageType
                    && Objects.equals(timeIn, other.timeIn)
                    && Objects.equals(timeOut, other.timeOut)
                    && Objects.equals(reportLabel(), other.reportLabel());
        }
    }

    public enum StageType {
        DRIVE_IN("Drive In"),
        SERVICE("Service"),
        POST("Post"),
        PARKING("Parking"),
        BACKYARD("Backyard"),
        TEST_DRIVE("Test-Drive");

        private final String reportLabel;

        StageType(String reportLabel) {
            this.reportLabel = reportLabel;
        }

        public String reportLabel() {
            return reportLabel;
        }
    }
}
