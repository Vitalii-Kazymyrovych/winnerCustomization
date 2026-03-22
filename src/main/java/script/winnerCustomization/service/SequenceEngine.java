package script.winnerCustomization.service;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageType;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class SequenceEngine {

    public List<SequenceRecord> build(List<Detection> detections, AppConfig config) {
        return build(detections, config, null);
    }

    public List<SequenceRecord> build(List<Detection> detections, AppConfig config, LocalDateTime reportGeneratedAt) {
        if (config == null) {
            return List.of();
        }
        Map<String, ActiveSequence> activeByPlate = new HashMap<>();
        Map<String, Detection> lastDetectionByPlate = new HashMap<>();
        List<SequenceRecord> finished = new ArrayList<>();

        List<Detection> ordered = detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();

        for (Detection rawDetection : ordered) {
            Detection detection = normalize(rawDetection, lastDetectionByPlate);
            if (isDuplicate(lastDetectionByPlate.get(detection.plateNumber()), detection, config)) {
                continue;
            }
            lastDetectionByPlate.put(detection.plateNumber(), detection);
            ActiveSequence sequence = activeByPlate.computeIfAbsent(detection.plateNumber(), plate -> new ActiveSequence(new SequenceRecord(plate, detection.createdAt())));
            if (closeBySequenceTimeout(sequence, detection.createdAt(), config)) {
                closeBySingleTimeout(sequence, detection.createdAt(), config);
                materializeCandidateIfDue(sequence, detection.createdAt());
                closeByTransitionalTimeout(sequence, detection.createdAt());
                finalizeSequence(sequence, sequence.lastActivityAt(), true);
                finished.add(sequence.record);
                sequence = new ActiveSequence(new SequenceRecord(detection.plateNumber(), detection.createdAt()));
                activeByPlate.put(detection.plateNumber(), sequence);
            }
            materializeCandidateIfDue(sequence, detection.createdAt());
            applyDetection(sequence, detection, config);
            sequence.lastDetectionAt = detection.createdAt();
        }

        for (ActiveSequence sequence : activeByPlate.values()) {
            if (reportGeneratedAt != null) {
                closeBySingleTimeout(sequence, reportGeneratedAt, config);
                materializeCandidateIfDue(sequence, reportGeneratedAt);
                closeByTransitionalTimeout(sequence, reportGeneratedAt);
            }
            boolean closed = isClosed(sequence);
            finalizeSequence(sequence, closed ? sequence.lastActivityAt() : null, closed);
            if (!sequence.record.getStages().isEmpty()) {
                finished.add(sequence.record);
            }
        }

        finished.sort(Comparator.comparing(SequenceRecord::getStartedAt).thenComparing(SequenceRecord::getPlateNumber));
        return finished;
    }

    private Detection normalize(Detection detection, Map<String, Detection> lastDetectionByPlate) {
        Detection previous = lastDetectionByPlate.get(detection.plateNumber());
        if (previous == null || detection.createdAt().isAfter(previous.createdAt())) {
            return detection;
        }
        return new Detection(detection.id(), detection.plateNumber(), detection.analyticsId(), detection.direction(), previous.createdAt().plusSeconds(1));
    }

    private boolean isDuplicate(Detection previous, Detection current, AppConfig config) {
        if (previous == null) {
            return false;
        }
        if (!Objects.equals(previous.analyticsId(), current.analyticsId()) || !Objects.equals(previous.direction(), current.direction())) {
            return false;
        }
        return Duration.between(previous.createdAt(), current.createdAt()).toSeconds() <= safeDuplicateSuppression(config);
    }

    private long safeDuplicateSuppression(AppConfig config) {
        return config.getDuplicateSuppressionSeconds() == null ? 2 : Math.max(0, config.getDuplicateSuppressionSeconds());
    }

    private void applyDetection(ActiveSequence sequence, Detection detection, AppConfig config) {
        RealMatch realIn = matchRealIn(detection, config);
        RealMatch realOut = matchRealOut(detection, config);
        AppConfig.SingleCameraStageConfig single = matchSingle(detection, config);

        if (single != null) {
            openOrRefreshSingle(sequence, single, detection, config);
            return;
        }

        if (realIn != null) {
            startRealStage(sequence, realIn, detection.createdAt());
            return;
        }

        if (realOut != null) {
            handleRealOut(sequence, realOut, detection, config);
            return;
        }

        maybeCreateTransitionalCandidateByCamera(sequence, detection, config);
    }

    private void startRealStage(ActiveSequence sequence, RealMatch realMatch, LocalDateTime eventTime) {
        cancelCandidateIfPending(sequence);
        if (sequence.activeStage != null && !Objects.equals(sequence.activeStage.stageName(), realMatch.config().getName())) {
            closeActiveStage(sequence, eventTime);
        }
        sequence.sequenceCloseTimeoutOverrideSeconds = null;
        if (sequence.activeStage == null || !Objects.equals(sequence.activeStage.stageName(), realMatch.config().getName())) {
            StageWindow stage = new StageWindow(realMatch.config().getName(), realMatch.config().getLabel(), StageType.REAL, eventTime, null, false, false, true);
            sequence.record.addStage(stage);
            sequence.activeStage = stage;
            sequence.activeSingleConfig = null;
            sequence.lastSingleDetectionAt = null;
        }
    }

    private void handleRealOut(ActiveSequence sequence, RealMatch realMatch, Detection detection, AppConfig config) {
        LocalDateTime eventTime = detection.createdAt();
        if (sequence.activeStage != null && Objects.equals(sequence.activeStage.stageName(), realMatch.config().getName()) && sequence.activeStage.stageType() == StageType.REAL) {
            sequence.activeStage.setTimeOut(eventTime);
            maybeCreateTransitionalCandidateByCamera(sequence, detection, config);
            maybeCreateTransitionalCandidate(sequence, eventTime, config, realMatch.config().getName(), false);
            clearActiveStage(sequence);
            return;
        }
        if (sequence.activeStage != null && sequence.activeStage.stageType() == StageType.SINGLE_CAMERA) {
            sequence.activeStage.setTimeOut(eventTime);
            maybeCreateTransitionalCandidateByCamera(sequence, detection, config);
            maybeCreateTransitionalCandidate(sequence, eventTime, config, sequence.activeStage.stageName(), true);
            clearActiveStage(sequence);
            return;
        }
        if (sequence.materializedCandidate != null) {
            sequence.materializedCandidate.setTimeOut(eventTime.minusSeconds(1));
            clearActiveStage(sequence);
        }
        cancelCandidateIfPending(sequence);
        sequence.record.addStage(new StageWindow(realMatch.config().getName(), realMatch.config().getLabel(), StageType.REAL, null, eventTime, true, false, true));
        maybeCreateTransitionalCandidateByCamera(sequence, detection, config);
    }

    private void openOrRefreshSingle(ActiveSequence sequence,
                                     AppConfig.SingleCameraStageConfig singleConfig,
                                     Detection detection,
                                     AppConfig config) {
        if (sequence.activeStage != null && (sequence.activeStage.stageType() != StageType.SINGLE_CAMERA
                || !Objects.equals(sequence.activeStage.stageName(), singleConfig.getName()))) {
            closeActiveStage(sequence, detection.createdAt());
        }
        if (sequence.activeStage == null) {
            StageWindow stage = new StageWindow(singleConfig.getName(), singleConfig.getLabel(), StageType.SINGLE_CAMERA, detection.createdAt(), null, false, false, true);
            stage.setLastSeenAt(detection.createdAt());
            sequence.record.addStage(stage);
            sequence.activeStage = stage;
        } else {
            sequence.activeStage.setLastSeenAt(detection.createdAt());
        }
        sequence.sequenceCloseTimeoutOverrideSeconds = null;
        sequence.activeSingleConfig = singleConfig;
        sequence.lastSingleDetectionAt = detection.createdAt();
    }

    private void maybeCreateTransitionalCandidateByCamera(ActiveSequence sequence, Detection detection, AppConfig config) {
        for (AppConfig.TransitionalStageConfig stage : safeTransitionalStages(config)) {
            if (stage.getTriggerCameras() != null && stage.getTriggerCameras().contains(detection.analyticsId())) {
                if (!transitionalAllowedFromCurrentContext(sequence, stage)) {
                    continue;
                }
                createCandidate(sequence, stage, detection.createdAt());
                return;
            }
        }
    }

    private void maybeCreateTransitionalCandidate(ActiveSequence sequence, LocalDateTime closureTime, AppConfig config, String closedStageName, boolean closedWasSingle) {
        if (closedStageName == null) {
            return;
        }
        if (closedWasSingle && !Boolean.TRUE.equals(config.getAllowTransitionalAfterSingleCamera())) {
            return;
        }
        for (AppConfig.TransitionalStageConfig stage : safeTransitionalStages(config)) {
            if (stage.getAllowedAfter() != null && stage.getAllowedAfter().contains(closedStageName)) {
                createCandidate(sequence, stage, closureTime.plusSeconds(1));
                return;
            }
        }
    }

    private void createCandidate(ActiveSequence sequence, AppConfig.TransitionalStageConfig transitionalConfig, LocalDateTime candidateTimeIn) {
        if (transitionalConfig.getCandidateTimeoutSeconds() == null) {
            return;
        }
        if (sequence.activeStage != null
                && sequence.activeStage.stageType() == StageType.TRANSITIONAL
                && Objects.equals(sequence.activeStage.stageName(), transitionalConfig.getName())) {
            sequence.activeStage.setLastSeenAt(candidateTimeIn);
            return;
        }
        if (sequence.pendingCandidate != null && Objects.equals(sequence.pendingCandidate.config().getName(), transitionalConfig.getName())) {
            sequence.pendingCandidate = new PendingCandidate(
                    transitionalConfig,
                    sequence.pendingCandidate.timeIn(),
                    candidateTimeIn.plusSeconds(transitionalConfig.getCandidateTimeoutSeconds()));
            return;
        }
        sequence.pendingCandidate = new PendingCandidate(transitionalConfig, candidateTimeIn, candidateTimeIn.plusSeconds(transitionalConfig.getCandidateTimeoutSeconds()));
    }

    private void materializeCandidateIfDue(ActiveSequence sequence, LocalDateTime boundary) {
        if (sequence.pendingCandidate == null || boundary == null) {
            return;
        }
        if (boundary.isBefore(sequence.pendingCandidate.materializeAt())) {
            return;
        }
        if (sequence.activeStage != null) {
            LocalDateTime closeAt = sequence.pendingCandidate.timeIn().minusSeconds(1);
            if (closeAt.isBefore(sequence.activeStage.timeIn() == null ? sequence.pendingCandidate.timeIn() : sequence.activeStage.timeIn())) {
                closeAt = sequence.pendingCandidate.timeIn();
            }
            closeActiveStage(sequence, closeAt);
        }
        boolean show = !Boolean.FALSE.equals(sequence.pendingCandidate.config().getShowInReportIfIncomplete())
                || positive(sequence.pendingCandidate.config().getSequenceCloseTimeoutOverrideSeconds()) == 0;
        StageWindow stage = new StageWindow(sequence.pendingCandidate.config().getName(),
                sequence.pendingCandidate.config().getLabel(),
                StageType.TRANSITIONAL,
                sequence.pendingCandidate.timeIn(),
                null,
                false,
                false,
                show);
        stage.setLastSeenAt(sequence.pendingCandidate.materializeAt());
        sequence.record.addStage(stage);
        sequence.activeStage = stage;
        sequence.materializedCandidate = stage;
        sequence.materializedCandidateConfig = sequence.pendingCandidate.config();
        sequence.sequenceCloseTimeoutOverrideSeconds = sequence.pendingCandidate.config().getSequenceCloseTimeoutOverrideSeconds();
        sequence.pendingCandidate = null;
    }

    private void closeBySingleTimeout(ActiveSequence sequence, LocalDateTime boundary, AppConfig config) {
        if (sequence.activeStage == null || sequence.activeStage.stageType() != StageType.SINGLE_CAMERA || boundary == null || sequence.activeSingleConfig == null || sequence.lastSingleDetectionAt == null) {
            return;
        }
        if (Duration.between(sequence.lastSingleDetectionAt, boundary).toSeconds() > sequence.activeSingleConfig.getTimeoutSeconds()) {
            String stageName = sequence.activeStage.stageName();
            sequence.activeStage.setTimeOut(sequence.lastSingleDetectionAt);
            sequence.activeStage = null;
            sequence.activeSingleConfig = null;
            maybeCreateTransitionalCandidate(sequence, sequence.lastSingleDetectionAt, config, stageName, true);
        }
    }

    private void closeByTransitionalTimeout(ActiveSequence sequence, LocalDateTime boundary) {
        if (sequence.activeStage == null
                || sequence.activeStage.stageType() != StageType.TRANSITIONAL
                || boundary == null
                || sequence.sequenceCloseTimeoutOverrideSeconds == null) {
            return;
        }
        LocalDateTime stageActivityAt = sequence.activeStage.lastSeenAt() != null
                ? sequence.activeStage.lastSeenAt()
                : sequence.activeStage.timeIn();
        if (stageActivityAt == null) {
            return;
        }
        if (Duration.between(stageActivityAt, boundary).toSeconds() > Math.max(0, sequence.sequenceCloseTimeoutOverrideSeconds)) {
            sequence.activeStage.setTimeOut(stageActivityAt);
            clearActiveStage(sequence);
        }
    }

    private void clearActiveStage(ActiveSequence sequence) {
        sequence.activeStage = null;
        sequence.activeSingleConfig = null;
        sequence.lastSingleDetectionAt = null;
        sequence.materializedCandidate = null;
        sequence.materializedCandidateConfig = null;
    }

    private boolean closeBySequenceTimeout(ActiveSequence sequence, LocalDateTime eventTime, AppConfig config) {
        if (sequence.lastDetectionAt == null || eventTime == null) {
            return false;
        }
        long timeoutSeconds = resolveSequenceTimeoutSeconds(sequence, config);
        return Duration.between(sequence.lastDetectionAt, eventTime).toSeconds() > timeoutSeconds;
    }

    private long resolveSequenceTimeoutSeconds(ActiveSequence sequence, AppConfig config) {
        if (sequence.sequenceCloseTimeoutOverrideSeconds != null) {
            return Math.max(0, sequence.sequenceCloseTimeoutOverrideSeconds);
        }
        return Math.max(1, positive(config.getSequenceCloseTimeoutMinutes()) * 60L);
    }

    private int positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private boolean isClosed(ActiveSequence sequence) {
        if (sequence.pendingCandidate != null) {
            return false;
        }
        if (sequence.activeStage == null) {
            return true;
        }
        return sequence.activeStage.timeOut() != null;
    }

    private void finalizeSequence(ActiveSequence sequence, LocalDateTime finishedAt, boolean closed) {
        if (sequence.record.getStages().isEmpty()) {
            return;
        }
        sequence.record.setClosed(closed);
        if (closed) {
            sequence.record.setFinishedAt(finishedAt);
        }
    }

    private void closeActiveStage(ActiveSequence sequence, LocalDateTime eventTime) {
        if (sequence.activeStage == null) {
            return;
        }
        if (sequence.activeStage.stageType() == StageType.SINGLE_CAMERA) {
            if (eventTime != null) {
                sequence.activeStage.setTimeOut(eventTime);
            } else if (sequence.lastSingleDetectionAt != null) {
                sequence.activeStage.setTimeOut(sequence.lastSingleDetectionAt);
            }
        } else if (sequence.activeStage.stageType() == StageType.REAL && sequence.activeStage.timeOut() != null) {
            // real stages keep sticky Out and are only logically closed by the next stage
        } else if (eventTime != null) {
            sequence.activeStage.setTimeOut(eventTime);
        }
        sequence.activeStage = null;
        sequence.activeSingleConfig = null;
        sequence.materializedCandidate = null;
        sequence.materializedCandidateConfig = null;
    }

    private void cancelCandidateIfPending(ActiveSequence sequence) {
        sequence.pendingCandidate = null;
    }

    private boolean transitionalAllowedFromCurrentContext(ActiveSequence sequence, AppConfig.TransitionalStageConfig config) {
        if (sequence.activeStage != null && Objects.equals(sequence.activeStage.stageName(), config.getName())) {
            return true;
        }
        if (config.getAllowedAfter() == null || config.getAllowedAfter().isEmpty()) {
            return true;
        }
        if (sequence.activeStage != null) {
            return config.getAllowedAfter().contains(sequence.activeStage.stageName());
        }
        for (int index = sequence.record.getStages().size() - 1; index >= 0; index--) {
            StageWindow previous = sequence.record.getStages().get(index);
            if (previous.partial()) {
                continue;
            }
            return config.getAllowedAfter().contains(previous.stageName());
        }
        return false;
    }

    private RealMatch matchRealIn(Detection detection, AppConfig config) {
        for (AppConfig.RealStageConfig stage : safeRealStages(config)) {
            if (matchesAny(stage.getInTriggers(), detection)) {
                return new RealMatch(stage);
            }
        }
        return null;
    }

    private RealMatch matchRealOut(Detection detection, AppConfig config) {
        for (AppConfig.RealStageConfig stage : safeRealStages(config)) {
            if (matchesAny(stage.getOutTriggers(), detection)) {
                return new RealMatch(stage);
            }
        }
        return null;
    }

    private AppConfig.SingleCameraStageConfig matchSingle(Detection detection, AppConfig config) {
        for (AppConfig.SingleCameraStageConfig stage : safeSingleStages(config)) {
            if (Objects.equals(stage.getCameraId(), detection.analyticsId())) {
                return stage;
            }
        }
        return null;
    }

    private boolean matchesAny(List<AppConfig.CameraTrigger> triggers, Detection detection) {
        if (triggers == null) {
            return false;
        }
        for (AppConfig.CameraTrigger trigger : triggers) {
            if (Objects.equals(trigger.getCameraId(), detection.analyticsId())
                    && matchesDirection(trigger.getDirectionRange(), detection.direction())) {
                return true;
            }
        }
        return false;
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

    private List<AppConfig.RealStageConfig> safeRealStages(AppConfig config) {
        return config.getRealStages() == null ? List.of() : config.getRealStages();
    }

    private List<AppConfig.TransitionalStageConfig> safeTransitionalStages(AppConfig config) {
        return config.getTransitionalStages() == null ? List.of() : config.getTransitionalStages();
    }

    private List<AppConfig.SingleCameraStageConfig> safeSingleStages(AppConfig config) {
        return config.getSingleCameraStages() == null ? List.of() : config.getSingleCameraStages();
    }

    private record RealMatch(AppConfig.RealStageConfig config) {}

    private record PendingCandidate(AppConfig.TransitionalStageConfig config,
                                    LocalDateTime timeIn,
                                    LocalDateTime materializeAt) {}

    private static final class ActiveSequence {
        private final SequenceRecord record;
        private StageWindow activeStage;
        private AppConfig.SingleCameraStageConfig activeSingleConfig;
        private LocalDateTime lastSingleDetectionAt;
        private LocalDateTime lastDetectionAt;
        private PendingCandidate pendingCandidate;
        private StageWindow materializedCandidate;
        private AppConfig.TransitionalStageConfig materializedCandidateConfig;
        private Integer sequenceCloseTimeoutOverrideSeconds;

        private ActiveSequence(SequenceRecord record) {
            this.record = record;
        }

        private LocalDateTime lastActivityAt() {
            if (activeStage != null && activeStage.stageType() == StageType.SINGLE_CAMERA && lastSingleDetectionAt != null) {
                return lastSingleDetectionAt;
            }
            if (activeStage != null && activeStage.lastSeenAt() != null) {
                return activeStage.lastSeenAt();
            }
            if (!record.getStages().isEmpty()) {
                StageWindow lastRecordedStage = record.getStages().getLast();
                if (lastRecordedStage.timeOut() != null) {
                    return lastRecordedStage.timeOut();
                }
                if (lastRecordedStage.lastSeenAt() != null) {
                    return lastRecordedStage.lastSeenAt();
                }
                if (lastRecordedStage.timeIn() != null) {
                    return lastRecordedStage.timeIn();
                }
            }
            if (lastDetectionAt != null) {
                return lastDetectionAt;
            }
            return record.getStartedAt();
        }
    }
}
