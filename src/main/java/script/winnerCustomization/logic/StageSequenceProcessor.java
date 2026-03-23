package script.winnerCustomization.logic;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class StageSequenceProcessor {

    public ProcessingResult process(List<Detection> detections, AppConfig config, LocalDateTime reportAt) {
        List<Detection> ordered = detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();
        Map<String, SequenceState> active = new LinkedHashMap<>();
        List<SequenceRecord> completed = new ArrayList<>();

        for (Detection detection : ordered) {
            expireTimedStates(active, detection.createdAt(), completed, config);
            SequenceState state = active.computeIfAbsent(detection.plateNumber(), plate -> new SequenceState(plate, detection.createdAt()));
            state.onDetection(detection, config);
            if (state.closed) {
                active.remove(detection.plateNumber());
                completed.add(state.toRecord(reportAt));
            }
        }

        expireTimedStates(active, reportAt, completed, config);
        for (SequenceState state : new ArrayList<>(active.values())) {
            state.finalizeForReport(reportAt, config);
            completed.add(state.toRecord(reportAt));
        }
        completed.sort(Comparator.comparing(SequenceRecord::getPlateNumber).thenComparing(SequenceRecord::getStartedAt));
        return new ProcessingResult(completed);
    }

    private void expireTimedStates(Map<String, SequenceState> active,
                                   LocalDateTime boundary,
                                   List<SequenceRecord> completed,
                                   AppConfig config) {
        for (SequenceState state : new ArrayList<>(active.values())) {
            state.advanceTime(boundary, config);
            if (state.closed) {
                active.remove(state.plate);
                completed.add(state.toRecord(boundary));
            }
        }
    }

    public record ProcessingResult(List<SequenceRecord> sequences) {
        public List<SequenceRecord.StageWindow> flattenedEvents() {
            List<SequenceRecord.StageWindow> events = new ArrayList<>();
            for (SequenceRecord sequence : sequences) {
                events.addAll(sequence.stagesChronologically());
            }
            return events;
        }
    }

    private static final class SequenceState {
        private final String plate;
        private final SequenceRecord record;
        private StageRuntime activeStage;
        private LocalDateTime lastDetectionAt;
        private boolean closed;

        private SequenceState(String plate, LocalDateTime startedAt) {
            this.plate = plate;
            this.record = new SequenceRecord(plate, startedAt);
            this.lastDetectionAt = startedAt;
        }

        private void onDetection(Detection detection, AppConfig config) {
            advanceTime(detection.createdAt(), config);
            if (closed) {
                return;
            }
            lastDetectionAt = detection.createdAt();

            Optional<AppConfig.SingleCameraStageConfig> singleMatch = matchSingle(detection, config);
            Optional<RealTriggerMatch> realIn = matchRealIn(detection, config);
            Optional<RealTriggerMatch> realOut = matchRealOut(detection, config);
            Optional<AppConfig.TransitionalStageConfig> transition = matchTransitionalTrigger(detection, config);

            if (singleMatch.isPresent()) {
                startOrUpdateSingle(singleMatch.get(), detection.createdAt(), config);
            } else if (realIn.isPresent()) {
                startReal(realIn.get().stage(), detection.createdAt(), config);
            } else if (realOut.isPresent()) {
                applyRealOut(realOut.get().stage(), detection.createdAt(), config);
            } else if (transition.isPresent()) {
                startOrUpdateTransitional(transition.get(), detection.createdAt(), config);
            }
        }

        private void advanceTime(LocalDateTime boundary, AppConfig config) {
            if (closed || boundary.isBefore(lastDetectionAt)) {
                return;
            }
            if (!lastDetectionAt.plusSeconds(resolveSequenceTimeoutSeconds(config)).isAfter(boundary)
                    && boundary.isAfter(lastDetectionAt)) {
                closeSequence(lastDetectionAt.plusSeconds(resolveSequenceTimeoutSeconds(config)), config);
            }
        }

        private long resolveSequenceTimeoutSeconds(AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.TRANSITIONAL
                    && activeStage.transitionalConfig != null
                    && activeStage.transitionalConfig.getSequenceCloseTimeoutOverrideSeconds() != null
                    && activeStage.transitionalConfig.getSequenceCloseTimeoutOverrideSeconds() > 0) {
                return activeStage.transitionalConfig.getSequenceCloseTimeoutOverrideSeconds();
            }
            return Duration.ofMinutes(config.getSequenceCloseTimeoutMinutes()).toSeconds();
        }

        private void finalizeForReport(LocalDateTime reportAt, AppConfig config) {
            advanceTime(reportAt, config);
        }

        private void startOrUpdateSingle(AppConfig.SingleCameraStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.SINGLE_CAMERA
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                activeStage.lastSeen = timestamp;
                return;
            }
            closeActiveBefore(timestamp, config, true);
            activeStage = new StageRuntime(stage.getName(), stage.getLabel(), SequenceRecord.StageType.SINGLE_CAMERA, timestamp);
            activeStage.singleConfig = stage;
            activeStage.lastSeen = timestamp;
        }

        private void startReal(AppConfig.RealStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.REAL
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                if (activeStage.timeOut != null && timestamp.isAfter(activeStage.timeOut)) {
                    closeActiveBefore(timestamp, config, false);
                } else {
                    return;
                }
            } else {
                closeActiveBefore(timestamp, config, true);
            }
            activeStage = new StageRuntime(stage.getName(), stage.getLabel(), SequenceRecord.StageType.REAL, timestamp);
            activeStage.realConfig = stage;
            activeStage.lastSeen = timestamp;
        }

        private void applyRealOut(AppConfig.RealStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.REAL
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                activeStage.timeOut = timestamp;
                activeStage.lastSeen = timestamp;
                return;
            }
            closeActiveBefore(timestamp, config, true);
            record.addStage(new SequenceRecord.StageWindow(stage.getName(), stage.getLabel(), SequenceRecord.StageType.REAL,
                    null, timestamp, true, false, true));
        }

        private void startOrUpdateTransitional(AppConfig.TransitionalStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.TRANSITIONAL
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                activeStage.lastSeen = timestamp;
                return;
            }
            closeActiveBefore(timestamp, config, false);
            activeStage = new StageRuntime(stage.getName(), stage.getLabel(), SequenceRecord.StageType.TRANSITIONAL, timestamp);
            activeStage.transitionalConfig = stage;
            activeStage.lastSeen = timestamp;
        }

        private void closeActiveBefore(LocalDateTime nextStageAt, AppConfig config, boolean allowImplicitTransition) {
            if (activeStage == null) {
                return;
            }
            StageRuntime closing = activeStage;
            activeStage = null;
            LocalDateTime stageOut = resolveOutForStageSwitch(closing, nextStageAt);
            record.addStage(new SequenceRecord.StageWindow(closing.stageName, closing.label, closing.type,
                    closing.timeIn, stageOut, false, false, true));
            if (allowImplicitTransition && stageOut != null) {
                maybeAddImplicitTransition(closing.stageName, stageOut, nextStageAt, config);
            }
        }

        private LocalDateTime resolveOutForStageSwitch(StageRuntime stage, LocalDateTime nextStageAt) {
            return switch (stage.type) {
                case SINGLE_CAMERA -> stage.lastSeen;
                case TRANSITIONAL -> nextStageAt.minusSeconds(1);
                case REAL -> stage.timeOut != null && !stage.timeOut.isAfter(nextStageAt)
                        ? stage.timeOut
                        : nextStageAt.minusSeconds(1);
            };
        }

        private void maybeAddImplicitTransition(String previousStageName,
                                                LocalDateTime previousStageOut,
                                                LocalDateTime nextStageAt,
                                                AppConfig config) {
            for (AppConfig.TransitionalStageConfig stage : config.getTransitionalStages()) {
                if (!stage.getAllowedAfter().contains(previousStageName)) {
                    continue;
                }
                LocalDateTime transitionStart = previousStageOut.plusSeconds(1);
                LocalDateTime earliestStableStart = transitionStart.plusSeconds(stage.getCandidateTimeoutSeconds());
                LocalDateTime transitionOut = nextStageAt.minusSeconds(1);
                if (!earliestStableStart.isAfter(nextStageAt) && !transitionStart.isAfter(transitionOut)) {
                    record.addStage(new SequenceRecord.StageWindow(stage.getName(), stage.getLabel(), SequenceRecord.StageType.TRANSITIONAL,
                            transitionStart, transitionOut, false, false, true));
                }
                return;
            }
        }

        private void closeSequence(LocalDateTime boundary, AppConfig config) {
            if (activeStage != null) {
                StageRuntime closing = activeStage;
                activeStage = null;
                LocalDateTime stageOut = resolveOutForSequenceClose(closing, boundary);
                record.addStage(new SequenceRecord.StageWindow(closing.stageName, closing.label, closing.type,
                        closing.timeIn, stageOut, false, false, true));
                if (stageOut != null) {
                    maybeAddImplicitTransition(closing.stageName, stageOut, boundary, config);
                }
            }
            record.setClosed(true);
            record.setFinishedAt(boundary);
            closed = true;
        }

        private LocalDateTime resolveOutForSequenceClose(StageRuntime stage, LocalDateTime boundary) {
            return switch (stage.type) {
                case SINGLE_CAMERA, TRANSITIONAL -> stage.lastSeen;
                case REAL -> stage.timeOut;
            };
        }

        private SequenceRecord toRecord(LocalDateTime reportAt) {
            if (!closed) {
                record.setFinishedAt(reportAt);
                if (activeStage != null) {
                    LocalDateTime stageOut = activeStage.type == SequenceRecord.StageType.SINGLE_CAMERA
                            || activeStage.type == SequenceRecord.StageType.TRANSITIONAL
                            ? activeStage.lastSeen
                            : activeStage.timeOut;
                    record.addStage(new SequenceRecord.StageWindow(activeStage.stageName, activeStage.label, activeStage.type,
                            activeStage.timeIn, stageOut, false, false, true));
                    activeStage = null;
                }
            }
            return record;
        }

        private Optional<AppConfig.SingleCameraStageConfig> matchSingle(Detection detection, AppConfig config) {
            return config.getSingleCameraStages().stream()
                    .filter(stage -> Objects.equals(stage.getCameraId(), detection.analyticsId()))
                    .findFirst();
        }

        private Optional<RealTriggerMatch> matchRealIn(Detection detection, AppConfig config) {
            return config.getRealStages().stream()
                    .filter(stage -> stage.getInTriggers().stream().anyMatch(trigger -> matchesTrigger(trigger, detection)))
                    .map(RealTriggerMatch::new)
                    .findFirst();
        }

        private Optional<RealTriggerMatch> matchRealOut(Detection detection, AppConfig config) {
            return config.getRealStages().stream()
                    .filter(stage -> stage.getOutTriggers().stream().anyMatch(trigger -> matchesTrigger(trigger, detection)))
                    .map(RealTriggerMatch::new)
                    .findFirst();
        }

        private Optional<AppConfig.TransitionalStageConfig> matchTransitionalTrigger(Detection detection, AppConfig config) {
            return config.getTransitionalStages().stream()
                    .filter(stage -> stage.getTriggerCameras().contains(detection.analyticsId()))
                    .findFirst();
        }

        private boolean matchesTrigger(AppConfig.CameraTrigger trigger, Detection detection) {
            if (!Objects.equals(trigger.getCameraId(), detection.analyticsId())) {
                return false;
            }
            if (trigger.getDirectionRange() == null || detection.direction() == null) {
                return true;
            }
            int direction = detection.direction();
            int from = trigger.getDirectionRange().getFrom();
            int to = trigger.getDirectionRange().getTo();
            return from < to ? direction >= from && direction < to : direction >= from || direction < to;
        }
    }

    private record RealTriggerMatch(AppConfig.RealStageConfig stage) {
    }

    private static final class StageRuntime {
        private final String stageName;
        private final String label;
        private final SequenceRecord.StageType type;
        private final LocalDateTime timeIn;
        private LocalDateTime timeOut;
        private LocalDateTime lastSeen;
        private AppConfig.RealStageConfig realConfig;
        private AppConfig.SingleCameraStageConfig singleConfig;
        private AppConfig.TransitionalStageConfig transitionalConfig;

        private StageRuntime(String stageName, String label, SequenceRecord.StageType type, LocalDateTime timeIn) {
            this.stageName = stageName;
            this.label = label;
            this.type = type;
            this.timeIn = timeIn;
            this.lastSeen = timeIn;
        }
    }
}
