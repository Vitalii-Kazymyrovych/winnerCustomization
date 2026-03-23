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
        private final Map<String, TransitionalCandidate> candidates = new LinkedHashMap<>();
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
            Optional<AppConfig.RealStageConfig> realIn = matchRealIn(detection, config);
            Optional<AppConfig.RealStageConfig> realOut = matchRealOut(detection, config);
            Optional<AppConfig.TransitionalStageConfig> transition = matchTransitionalTrigger(detection, config);

            if (singleMatch.isPresent()) {
                startOrUpdateSingle(singleMatch.get(), detection.createdAt(), config);
            } else if (realIn.isPresent()) {
                startReal(realIn.get(), detection.createdAt(), config);
            } else if (realOut.isPresent()) {
                applyRealOut(realOut.get(), detection.createdAt(), config);
            } else if (transition.isPresent()) {
                registerTransitionalCandidate(transition.get(), detection.createdAt(), CandidateSource.DETECTION, null, null);
                if (activeStage != null
                        && activeStage.type == SequenceRecord.StageType.TRANSITIONAL
                        && Objects.equals(activeStage.stageName, transition.get().getName())) {
                    activeStage.lastSeen = detection.createdAt();
                }
            }
        }

        private void advanceTime(LocalDateTime boundary, AppConfig config) {
            if (closed || boundary.isBefore(lastDetectionAt)) {
                return;
            }
            boolean progressed;
            do {
                progressed = false;

                if (expireSingleStage(boundary, config)) {
                    progressed = true;
                    continue;
                }

                TransitionalCandidate dueCandidate = earliestDueCandidate(boundary);
                if (dueCandidate != null) {
                    materializeCandidate(dueCandidate, config);
                    progressed = true;
                    continue;
                }

                long timeoutSeconds = resolveSequenceTimeoutSeconds(config);
                LocalDateTime closeAt = lastDetectionAt.plusSeconds(timeoutSeconds);
                if (boundary.isAfter(lastDetectionAt) && !closeAt.isAfter(boundary)) {
                    closeSequence(closeAt);
                    progressed = true;
                }
            } while (progressed && !closed);
        }

        private boolean expireSingleStage(LocalDateTime boundary, AppConfig config) {
            if (activeStage == null || activeStage.type != SequenceRecord.StageType.SINGLE_CAMERA) {
                return false;
            }
            LocalDateTime expiresAt = activeStage.lastSeen.plusSeconds(activeStage.singleConfig.getTimeoutSeconds());
            if (expiresAt.isAfter(boundary)) {
                return false;
            }
            StageRuntime closing = activeStage;
            activeStage = null;
            addClosedStage(closing, closing.lastSeen, true);
            createStageEndCandidates(closing.stageName, closing.lastSeen, config);
            return true;
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
            cancelPendingCandidates(timestamp, null);
            closeActiveForStageStart(timestamp, config);
            activeStage = new StageRuntime(stage.getName(), stage.getLabel(), SequenceRecord.StageType.SINGLE_CAMERA, timestamp);
            activeStage.singleConfig = stage;
            activeStage.lastSeen = timestamp;
        }

        private void startReal(AppConfig.RealStageConfig stage, LocalDateTime timestamp, AppConfig config) {
            if (activeStage != null
                    && activeStage.type == SequenceRecord.StageType.REAL
                    && Objects.equals(activeStage.stageName, stage.getName())) {
                if (activeStage.hasOutTrigger && activeStage.timeOut != null && timestamp.isAfter(activeStage.timeOut)) {
                    cancelPendingCandidates(timestamp, null);
                    closeActiveForStageStart(timestamp, config);
                } else {
                    return;
                }
            } else {
                cancelPendingCandidates(timestamp, null);
                closeActiveForStageStart(timestamp, config);
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
                activeStage.hasOutTrigger = true;
                activeStage.lastSeen = timestamp;
                createStageEndCandidates(stage.getName(), timestamp, config);
                return;
            }
            cancelPendingCandidates(timestamp, null);
            if (activeStage != null && activeStage.type == SequenceRecord.StageType.TRANSITIONAL) {
                closeActiveForStageStart(timestamp, config);
            }
            record.addStage(new SequenceRecord.StageWindow(stage.getName(), stage.getLabel(), SequenceRecord.StageType.REAL,
                    null, timestamp, true, false, true));
        }

        private TransitionalCandidate earliestDueCandidate(LocalDateTime boundary) {
            return candidates.values().stream()
                    .filter(candidate -> !candidate.dueAt().isAfter(boundary))
                    .min(Comparator.comparing(TransitionalCandidate::dueAt)
                            .thenComparing(TransitionalCandidate::stageStart)
                            .thenComparing(candidate -> candidate.config().getName()))
                    .orElse(null);
        }

        private void materializeCandidate(TransitionalCandidate candidate, AppConfig config) {
            candidates.remove(candidate.config().getName());
            if (!candidate.allowedAfterSatisfied()) {
                return;
            }
            LocalDateTime stageStart = candidate.stageStart();
            if (activeStage != null && !canCloseActiveFor(stageStart)) {
                return;
            }
            closeActiveForStageStart(stageStart, config);
            cancelPendingCandidates(stageStart, candidate.config().getName());
            activeStage = new StageRuntime(candidate.config().getName(), candidate.config().getLabel(), SequenceRecord.StageType.TRANSITIONAL, stageStart);
            activeStage.transitionalConfig = candidate.config();
            activeStage.lastSeen = candidate.lastSourceAt();
        }

        private boolean canCloseActiveFor(LocalDateTime nextStageAt) {
            if (activeStage == null) {
                return true;
            }
            LocalDateTime stageOut = resolveOutForStageSwitch(activeStage, nextStageAt);
            return stageOut == null || stageOut.isBefore(nextStageAt);
        }

        private void cancelPendingCandidates(LocalDateTime stageStart, String exceptStageName) {
            candidates.entrySet().removeIf(entry -> !Objects.equals(entry.getKey(), exceptStageName)
                    && stageStart.isBefore(entry.getValue().dueAt()));
        }

        private void closeActiveForStageStart(LocalDateTime nextStageAt, AppConfig config) {
            if (activeStage == null) {
                return;
            }
            StageRuntime closing = activeStage;
            LocalDateTime stageOut = resolveOutForStageSwitch(closing, nextStageAt);
            if (stageOut != null && !stageOut.isBefore(nextStageAt) && closing.type != SequenceRecord.StageType.REAL) {
                return;
            }
            activeStage = null;
            addClosedStage(closing, stageOut, true);
        }

        private void addClosedStage(StageRuntime stage, LocalDateTime stageOut, boolean visibleInReport) {
            record.addStage(new SequenceRecord.StageWindow(stage.stageName, stage.label, stage.type,
                    stage.timeIn, stageOut, false, false, visibleInReport));
        }

        private LocalDateTime resolveOutForStageSwitch(StageRuntime stage, LocalDateTime nextStageAt) {
            return switch (stage.type) {
                case SINGLE_CAMERA -> stage.lastSeen;
                case TRANSITIONAL -> nextStageAt.minusSeconds(1);
                case REAL -> stage.hasOutTrigger && stage.timeOut != null && !stage.timeOut.isAfter(nextStageAt)
                        ? stage.timeOut
                        : nextStageAt.minusSeconds(1);
            };
        }

        private void createStageEndCandidates(String previousStageName, LocalDateTime previousStageOut, AppConfig config) {
            if (previousStageName == null || previousStageOut == null) {
                return;
            }
            for (AppConfig.TransitionalStageConfig stage : config.getTransitionalStages()) {
                if (stage.getAllowedAfter().contains(previousStageName)) {
                    registerTransitionalCandidate(stage, previousStageOut, CandidateSource.STAGE_END, previousStageName, previousStageOut);
                }
            }
        }

        private void registerTransitionalCandidate(AppConfig.TransitionalStageConfig stage,
                                                   LocalDateTime eventAt,
                                                   CandidateSource source,
                                                   String previousStageName,
                                                   LocalDateTime previousStageOut) {
            TransitionalCandidate existing = candidates.get(stage.getName());
            if (existing != null) {
                existing.lastSourceAt = eventAt;
                if (source == CandidateSource.STAGE_END) {
                    existing.source = CandidateSource.STAGE_END;
                    existing.previousStageName = previousStageName;
                    existing.previousStageOut = previousStageOut;
                    existing.firstTriggeredAt = eventAt;
                }
                return;
            }
            candidates.put(stage.getName(), new TransitionalCandidate(stage, source, eventAt, eventAt, previousStageName, previousStageOut));
        }

        private void closeSequence(LocalDateTime boundary) {
            candidates.clear();
            if (activeStage != null) {
                StageRuntime closing = activeStage;
                activeStage = null;
                LocalDateTime stageOut = resolveOutForSequenceClose(closing);
                boolean visible = closing.type != SequenceRecord.StageType.TRANSITIONAL
                        || Boolean.TRUE.equals(closing.transitionalConfig.getShowInReportIfIncomplete());
                if (visible) {
                    addClosedStage(closing, stageOut, true);
                }
            }
            record.setClosed(true);
            record.setFinishedAt(boundary);
            closed = true;
        }

        private LocalDateTime resolveOutForSequenceClose(StageRuntime stage) {
            return switch (stage.type) {
                case SINGLE_CAMERA, REAL -> null;
                case TRANSITIONAL -> stage.lastSeen;
            };
        }

        private SequenceRecord toRecord(LocalDateTime reportAt) {
            if (!closed) {
                record.setFinishedAt(reportAt);
                if (activeStage != null) {
                    record.addStage(new SequenceRecord.StageWindow(activeStage.stageName, activeStage.label, activeStage.type,
                            activeStage.timeIn, null, false, false, true));
                }
            }
            return record;
        }

        private Optional<AppConfig.SingleCameraStageConfig> matchSingle(Detection detection, AppConfig config) {
            return config.getSingleCameraStages().stream()
                    .filter(stage -> Objects.equals(stage.getCameraId(), detection.analyticsId()))
                    .findFirst();
        }

        private Optional<AppConfig.RealStageConfig> matchRealIn(Detection detection, AppConfig config) {
            return config.getRealStages().stream()
                    .filter(stage -> stage.getInTriggers().stream().anyMatch(trigger -> matches(trigger, detection)))
                    .findFirst();
        }

        private Optional<AppConfig.RealStageConfig> matchRealOut(Detection detection, AppConfig config) {
            return config.getRealStages().stream()
                    .filter(stage -> stage.getOutTriggers().stream().anyMatch(trigger -> matches(trigger, detection)))
                    .findFirst();
        }

        private Optional<AppConfig.TransitionalStageConfig> matchTransitionalTrigger(Detection detection, AppConfig config) {
            return config.getTransitionalStages().stream()
                    .filter(stage -> stage.getTriggerCameras().contains(detection.analyticsId()))
                    .findFirst();
        }

        private boolean matches(AppConfig.CameraTrigger trigger, Detection detection) {
            if (!Objects.equals(trigger.getCameraId(), detection.analyticsId())) {
                return false;
            }
            if (trigger.getDirectionRange() == null) {
                return true;
            }
            if (detection.direction() == null) {
                return false;
            }
            int direction = detection.direction();
            return direction >= trigger.getDirectionRange().getFrom() && direction < trigger.getDirectionRange().getTo();
        }
    }

    private enum CandidateSource {
        DETECTION,
        STAGE_END
    }

    private static final class TransitionalCandidate {
        private final AppConfig.TransitionalStageConfig config;
        private CandidateSource source;
        private LocalDateTime firstTriggeredAt;
        private LocalDateTime lastSourceAt;
        private String previousStageName;
        private LocalDateTime previousStageOut;

        private TransitionalCandidate(AppConfig.TransitionalStageConfig config,
                                      CandidateSource source,
                                      LocalDateTime firstTriggeredAt,
                                      LocalDateTime lastSourceAt,
                                      String previousStageName,
                                      LocalDateTime previousStageOut) {
            this.config = config;
            this.source = source;
            this.firstTriggeredAt = firstTriggeredAt;
            this.lastSourceAt = lastSourceAt;
            this.previousStageName = previousStageName;
            this.previousStageOut = previousStageOut;
        }

        private AppConfig.TransitionalStageConfig config() {
            return config;
        }

        private LocalDateTime dueAt() {
            return lastSourceAt.plusSeconds(config.getCandidateTimeoutSeconds());
        }

        private LocalDateTime stageStart() {
            return source == CandidateSource.STAGE_END && previousStageOut != null
                    ? previousStageOut.plusSeconds(1)
                    : firstTriggeredAt;
        }

        private LocalDateTime lastSourceAt() {
            return lastSourceAt;
        }

        private boolean allowedAfterSatisfied() {
            return previousStageName == null || config.getAllowedAfter().contains(previousStageName);
        }
    }

    private static final class StageRuntime {
        private final String stageName;
        private final String label;
        private final SequenceRecord.StageType type;
        private final LocalDateTime timeIn;
        private LocalDateTime timeOut;
        private LocalDateTime lastSeen;
        private boolean hasOutTrigger;
        private AppConfig.RealStageConfig realConfig;
        private AppConfig.TransitionalStageConfig transitionalConfig;
        private AppConfig.SingleCameraStageConfig singleConfig;

        private StageRuntime(String stageName, String label, SequenceRecord.StageType type, LocalDateTime timeIn) {
            this.stageName = stageName;
            this.label = label;
            this.type = type;
            this.timeIn = timeIn;
            this.lastSeen = timeIn;
        }
    }
}
