package script.winnerCustomization.logic;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
        private TransitionalCandidate candidate;
        private LocalDateTime lastDetectionAt;
        private LocalDateTime nextStageStartHint;
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
            Optional<AppConfig.TransitionalStageConfig> explicitTransition = matchTransitionalTrigger(detection, config);

            if (singleMatch.isPresent()) {
                startOrUpdateSingle(singleMatch.get(), detection.createdAt(), config);
            } else if (realIn.isPresent()) {
                startReal(realIn.get().stage(), detection.createdAt(), config);
            } else if (realOut.isPresent()) {
                applyRealOut(realOut.get().stage(), detection.createdAt(), config);
            } else if (explicitTransition.isPresent()) {
                if (activeStage != null
                        && activeStage.type == SequenceRecord.StageType.TRANSITIONAL
                        && Objects.equals(activeStage.stageName, explicitTransition.get().getName())) {
                    activeStage.lastSeen = detection.createdAt();
                } else {
                    touchCandidate(explicitTransition.get(), detection.createdAt(), false, null, config);
                }
            }

            cancelNotificationsByOtherCamera(detection, config);
        }

        private void cancelNotificationsByOtherCamera(Detection detection, AppConfig config) {
            // notification planner operates separately; sequence engine only stores report alerts.
        }

        private void advanceTime(LocalDateTime boundary, AppConfig config) {
            boolean progressed;
            do {
                progressed = false;
                if (activeStage != null && activeStage.type == SequenceRecord.StageType.SINGLE_CAMERA) {
                    LocalDateTime timeoutAt = activeStage.lastSeen.plusSeconds(activeStage.singleConfig.getTimeoutSeconds());
                    if (!timeoutAt.isAfter(boundary)) {
                        nextStageStartHint = activeStage.lastSeen.plusSeconds(1);
                        closeActiveAt(activeStage.lastSeen, false, config, true);
                        progressed = true;
                        continue;
                    }
                }
                if (candidate != null && !candidate.materializedAt.isPresent() && !candidate.dueAt().isAfter(boundary)) {
                    materializeCandidate(candidate, config);
                    progressed = true;
                    continue;
                }
                LocalDateTime sequenceTimeoutAt = lastDetectionAt.plusSeconds(resolveSequenceTimeoutSeconds(config));
                if (!sequenceTimeoutAt.isAfter(boundary) && boundary.isAfter(lastDetectionAt)) {
                    closeSequence(sequenceTimeoutAt, config);
                    progressed = true;
                }
            } while (progressed && !closed);
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
            startNewStage(stage.getName(), stage.getLabel(), SequenceRecord.StageType.SINGLE_CAMERA,
                    timestamp, false, null, stage, config, true);
        }

        private void startReal(AppConfig.RealStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.REAL
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                if (activeStage.timeOut != null && timestamp.isAfter(activeStage.timeOut)) {
                    closeActiveAt(activeStage.timeOut, false, config, true);
                } else {
                    return;
                }
            }
            startNewStage(stage.getName(), stage.getLabel(), SequenceRecord.StageType.REAL,
                    timestamp, false, stage, null, config, true);
        }

        private void applyRealOut(AppConfig.RealStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.REAL
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                activeStage.timeOut = timestamp;
                activeStage.lastSeen = timestamp;
                return;
            }
            if (activeStage != null) {
                closeActiveAt(timestamp.minusSeconds(1), false, config, true);
            }
            record.addStage(new SequenceRecord.StageWindow(stage.getName(), stage.getLabel(), SequenceRecord.StageType.REAL,
                    null, timestamp, true, false, true));
        }

        private void startNewStage(String stageName,
                                   String label,
                                   SequenceRecord.StageType type,
                                   LocalDateTime timeIn,
                                   boolean candidateStage,
                                   AppConfig.RealStageConfig realConfig,
                                   AppConfig.SingleCameraStageConfig singleConfig,
                                   AppConfig config,
                                   boolean closePreviousMinusOneSecond) {
            LocalDateTime effectiveTimeIn = nextStageStartHint != null && nextStageStartHint.isBefore(timeIn) ? nextStageStartHint : timeIn;
            nextStageStartHint = null;
            if (activeStage != null) {
                LocalDateTime closeAt = closePreviousMinusOneSecond ? effectiveTimeIn.minusSeconds(1) : effectiveTimeIn;
                closeActiveAt(closeAt, false, config, true);
            }
            cancelCandidateBecauseOfNewStage(stageName, effectiveTimeIn);
            activeStage = new StageRuntime(stageName, label, type, effectiveTimeIn);
            activeStage.realConfig = realConfig;
            activeStage.singleConfig = singleConfig;
            activeStage.lastSeen = effectiveTimeIn;
        }

        private void cancelCandidateBecauseOfNewStage(String stageName, LocalDateTime timestamp) {
            if (candidate == null) {
                return;
            }
            if (candidate.stage.getName().equals(stageName) && candidate.sameSourceRepeatAllowed(timestamp)) {
                candidate.refresh(timestamp);
                return;
            }
            if (timestamp.isBefore(candidate.dueAt())) {
                candidate = null;
            } else if (!candidate.materializedAt.isPresent()) {
                materializeCandidate(candidate, null);
                candidate = null;
            }
        }

        private void touchCandidate(AppConfig.TransitionalStageConfig stage,
                                    LocalDateTime eventTimestamp,
                                    boolean fromStageEnd,
                                    String sourceStageName,
                                    AppConfig config) {
            if (candidate != null && candidate.stage.getName().equals(stage.getName())) {
                candidate.refresh(eventTimestamp);
                return;
            }
            if (candidate != null && eventTimestamp.isBefore(candidate.dueAt())) {
                candidate = null;
            }
            candidate = new TransitionalCandidate(stage, eventTimestamp, fromStageEnd, sourceStageName);
            if (config != null) {
                advanceTime(eventTimestamp, config);
            }
        }

        private void materializeCandidate(TransitionalCandidate pending, AppConfig config) {
            candidate = null;
            if (activeStage != null) {
                closeActiveAt(pending.startAt().minusSeconds(1), false, config, false);
            }
            activeStage = new StageRuntime(pending.stage.getName(), pending.stage.getLabel(), SequenceRecord.StageType.TRANSITIONAL, pending.startAt());
            activeStage.transitionalConfig = pending.stage;
            activeStage.lastSeen = pending.lastSourceTimestamp;
        }

        private void closeActiveAt(LocalDateTime timestamp, boolean dueToSequenceClose, AppConfig config, boolean createCandidates) {
            if (activeStage == null) {
                return;
            }
            LocalDateTime out = activeStage.type == SequenceRecord.StageType.REAL && dueToSequenceClose ? null : timestamp;
            if (activeStage.type == SequenceRecord.StageType.SINGLE_CAMERA && dueToSequenceClose) {
                out = null;
            }
            if (activeStage.type == SequenceRecord.StageType.TRANSITIONAL && dueToSequenceClose) {
                boolean showIncomplete = Boolean.TRUE.equals(activeStage.transitionalConfig.getShowInReportIfIncomplete());
                if (!showIncomplete) {
                    activeStage = null;
                    return;
                }
                out = null;
            }
            record.addStage(new SequenceRecord.StageWindow(activeStage.stageName, activeStage.label, activeStage.type,
                    activeStage.timeIn, out, false, false, true));
            if (createCandidates && out != null) {
                createCandidatesFromStageEnd(activeStage.stageName, out, config);
            }
            activeStage = null;
        }

        private void createCandidatesFromStageEnd(String stageName, LocalDateTime eventTimestamp, AppConfig config) {
            if (config == null) {
                return;
            }
            for (AppConfig.TransitionalStageConfig stage : config.getTransitionalStages()) {
                if (stage.getAllowedAfter().contains(stageName)) {
                    touchCandidate(stage, eventTimestamp, true, stageName, config);
                }
            }
        }

        private void closeSequence(LocalDateTime boundary, AppConfig config) {
            if (candidate != null && !candidate.materializedAt.isPresent()) {
                candidate = null;
            }
            if (activeStage != null) {
                closeActiveAt(boundary, true, config, true);
            }
            record.setClosed(true);
            record.setFinishedAt(boundary);
            closed = true;
        }

        private SequenceRecord toRecord(LocalDateTime reportAt) {
            if (!closed) {
                record.setFinishedAt(reportAt);
                if (activeStage != null) {
                    record.addStage(new SequenceRecord.StageWindow(activeStage.stageName, activeStage.label, activeStage.type,
                            activeStage.timeIn, null, false, false, true));
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

    private static final class TransitionalCandidate {
        private final AppConfig.TransitionalStageConfig stage;
        private final boolean fromStageEnd;
        private final String sourceStageName;
        private final LocalDateTime createdAt;
        private LocalDateTime lastSourceTimestamp;
        private Optional<LocalDateTime> materializedAt = Optional.empty();

        private TransitionalCandidate(AppConfig.TransitionalStageConfig stage,
                                      LocalDateTime eventTimestamp,
                                      boolean fromStageEnd,
                                      String sourceStageName) {
            this.stage = stage;
            this.createdAt = eventTimestamp;
            this.lastSourceTimestamp = eventTimestamp;
            this.fromStageEnd = fromStageEnd;
            this.sourceStageName = sourceStageName;
        }

        private LocalDateTime startAt() {
            return fromStageEnd ? createdAt.plusSeconds(1) : createdAt;
        }

        private LocalDateTime dueAt() {
            return lastSourceTimestamp.plusSeconds(stage.getCandidateTimeoutSeconds());
        }

        private boolean sameSourceRepeatAllowed(LocalDateTime timestamp) {
            return !timestamp.isBefore(lastSourceTimestamp);
        }

        private void refresh(LocalDateTime timestamp) {
            lastSourceTimestamp = timestamp;
        }
    }
}
