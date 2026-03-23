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

            ActiveSequence sequence = activeByPlate.computeIfAbsent(
                    detection.plateNumber(),
                    plate -> new ActiveSequence(new SequenceRecord(plate, detection.createdAt())));

            if (advanceUntil(sequence, detection.createdAt(), config)) {
                finishRecord(sequence, sequence.closedAt, true);
                finished.add(sequence.record);
                sequence = new ActiveSequence(new SequenceRecord(detection.plateNumber(), detection.createdAt()));
                activeByPlate.put(detection.plateNumber(), sequence);
            }

            applyDetection(sequence, detection, config);
            sequence.lastDetectionAt = detection.createdAt();
        }

        for (ActiveSequence sequence : activeByPlate.values()) {
            if (reportGeneratedAt != null && advanceUntil(sequence, reportGeneratedAt, config)) {
                finishRecord(sequence, sequence.closedAt, true);
            } else {
                finishRecord(sequence, null, false);
            }
            if (!sequence.record.getStages().isEmpty()) {
                finished.add(sequence.record);
            }
        }

        finished.sort(Comparator.comparing(SequenceRecord::getStartedAt).thenComparing(SequenceRecord::getPlateNumber));
        return finished;
    }

    private boolean advanceUntil(ActiveSequence sequence, LocalDateTime boundary, AppConfig config) {
        if (boundary == null) {
            return false;
        }
        while (true) {
            LocalDateTime nextSingleTimeout = singleTimeoutAt(sequence);
            LocalDateTime nextCandidateMaterialization = sequence.pendingCandidate == null ? null : sequence.pendingCandidate.materializeAt();
            LocalDateTime nextTransitionalClosure = transitionalCloseAt(sequence);
            LocalDateTime nextSequenceClosure = sequenceTimeoutAt(sequence, config);

            LocalDateTime nextBoundary = earliest(nextSingleTimeout, nextCandidateMaterialization, nextTransitionalClosure, nextSequenceClosure);
            if (nextBoundary == null || nextBoundary.isAfter(boundary)) {
                return false;
            }

            if (sameMoment(nextBoundary, nextSingleTimeout)) {
                closeSingleByTimeout(sequence, config);
                continue;
            }
            if (sameMoment(nextBoundary, nextCandidateMaterialization)) {
                materializeCandidate(sequence);
                continue;
            }
            if (sameMoment(nextBoundary, nextTransitionalClosure)) {
                closeTransitionalByTimeout(sequence, nextBoundary);
                sequence.closedAt = nextBoundary;
                return true;
            }
            if (sameMoment(nextBoundary, nextSequenceClosure)) {
                finalizeActiveStateForSequenceClose(sequence);
                sequence.closedAt = nextBoundary;
                return true;
            }
        }
    }

    private Detection normalize(Detection detection, Map<String, Detection> lastDetectionByPlate) {
        Detection previous = lastDetectionByPlate.get(detection.plateNumber());
        if (previous == null || detection.createdAt().isAfter(previous.createdAt())) {
            return detection;
        }
        return new Detection(
                detection.id(),
                detection.plateNumber(),
                detection.analyticsId(),
                detection.direction(),
                previous.createdAt().plusSeconds(1));
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

        if (realIn != null) {
            startRealStage(sequence, realIn.config(), detection.createdAt());
            return;
        }
        if (single != null) {
            openOrRefreshSingle(sequence, single, detection.createdAt());
            return;
        }
        if (realOut != null) {
            handleRealOut(sequence, realOut.config(), detection.createdAt(), config);
            return;
        }
        maybeCreateTransitionalCandidateByCamera(sequence, detection, config);
    }

    private void startRealStage(ActiveSequence sequence, AppConfig.RealStageConfig config, LocalDateTime eventTime) {
        if (sequence.activeStage != null
                && sequence.activeStage.type == StageType.REAL
                && Objects.equals(sequence.activeStage.window.stageName(), config.getName())) {
            if (sequence.activeStage.stickyOutAt != null && !eventTime.isBefore(sequence.activeStage.stickyOutAt)) {
                clearActiveStage(sequence);
            } else {
                return;
            }
        } else {
            cancelPendingCandidate(sequence);
            closeActiveForNextStage(sequence, eventTime);
        }

        StageWindow stage = new StageWindow(config.getName(), config.getLabel(), StageType.REAL, eventTime, null, false, false, true);
        sequence.record.addStage(stage);
        sequence.activeStage = ActiveStage.real(stage, config);
    }

    private void openOrRefreshSingle(ActiveSequence sequence, AppConfig.SingleCameraStageConfig config, LocalDateTime eventTime) {
        if (sequence.activeStage != null
                && sequence.activeStage.type == StageType.SINGLE_CAMERA
                && Objects.equals(sequence.activeStage.window.stageName(), config.getName())) {
            sequence.activeStage.window.setLastSeenAt(eventTime);
            sequence.activeStage.lastSeenAt = eventTime;
            return;
        }

        cancelPendingCandidate(sequence);
        closeActiveForNextStage(sequence, eventTime);

        StageWindow stage = new StageWindow(config.getName(), config.getLabel(), StageType.SINGLE_CAMERA, eventTime, null, false, false, true);
        stage.setLastSeenAt(eventTime);
        sequence.record.addStage(stage);
        sequence.activeStage = ActiveStage.single(stage, config, eventTime);
    }

    private void handleRealOut(ActiveSequence sequence,
                               AppConfig.RealStageConfig config,
                               LocalDateTime eventTime,
                               AppConfig appConfig) {
        if (sequence.activeStage != null
                && sequence.activeStage.type == StageType.REAL
                && Objects.equals(sequence.activeStage.window.stageName(), config.getName())) {
            sequence.activeStage.stickyOutAt = eventTime;
            sequence.activeStage.window.setTimeOut(eventTime);
            sequence.activeStage.window.setLastSeenAt(eventTime);
            maybeCreateTransitionalCandidate(sequence, eventTime.plusSeconds(1), eventTime, config.getName(), false, appConfig);
            return;
        }

        if (sequence.activeStage != null && sequence.activeStage.type == StageType.TRANSITIONAL) {
            setStageOutIfEarlier(sequence.activeStage.window, eventTime.minusSeconds(1));
            clearActiveStage(sequence);
        }

        sequence.record.addStage(new StageWindow(config.getName(), config.getLabel(), StageType.REAL, null, eventTime, true, false, true));
        maybeCreateTransitionalCandidateByCamera(sequence, new Detection(0L, sequence.record.getPlateNumber(), config.getOutTriggers().isEmpty() ? null : config.getOutTriggers().getFirst().getCameraId(), null, eventTime), appConfig);
    }

    private void maybeCreateTransitionalCandidateByCamera(ActiveSequence sequence, Detection detection, AppConfig config) {
        for (AppConfig.TransitionalStageConfig stage : safeTransitionalStages(config)) {
            if (stage.getTriggerCameras() == null || !stage.getTriggerCameras().contains(detection.analyticsId())) {
                continue;
            }
            if (!transitionalAllowedFromCurrentContext(sequence, stage)) {
                continue;
            }
            createOrRefreshCandidate(sequence, stage, detection.createdAt(), detection.createdAt(), "camera:" + detection.analyticsId());
            return;
        }
    }

    private void maybeCreateTransitionalCandidate(ActiveSequence sequence,
                                                  LocalDateTime timeIn,
                                                  LocalDateTime resetBase,
                                                  String closedStageName,
                                                  boolean closedWasSingle,
                                                  AppConfig config) {
        if (closedStageName == null) {
            return;
        }
        if (closedWasSingle && !Boolean.TRUE.equals(config.getAllowTransitionalAfterSingleCamera())) {
            return;
        }
        for (AppConfig.TransitionalStageConfig stage : safeTransitionalStages(config)) {
            if (stage.getAllowedAfter() != null && stage.getAllowedAfter().contains(closedStageName)) {
                createOrRefreshCandidate(sequence, stage, timeIn, resetBase, "after:" + closedStageName);
                return;
            }
        }
    }

    private void createOrRefreshCandidate(ActiveSequence sequence,
                                          AppConfig.TransitionalStageConfig config,
                                          LocalDateTime timeIn,
                                          LocalDateTime resetBase,
                                          String sourceKey) {
        if (config.getCandidateTimeoutSeconds() == null) {
            return;
        }
        LocalDateTime materializeAt = resetBase.plusSeconds(config.getCandidateTimeoutSeconds());
        if (sequence.pendingCandidate != null
                && Objects.equals(sequence.pendingCandidate.config().getName(), config.getName())
                && Objects.equals(sequence.pendingCandidate.sourceKey(), sourceKey)) {
            sequence.pendingCandidate = new PendingCandidate(config, sequence.pendingCandidate.timeIn(), materializeAt, sourceKey);
            return;
        }
        sequence.pendingCandidate = new PendingCandidate(config, timeIn, materializeAt, sourceKey);
    }

    private void materializeCandidate(ActiveSequence sequence) {
        if (sequence.pendingCandidate == null) {
            return;
        }
        closeActiveForNextStage(sequence, sequence.pendingCandidate.timeIn());
        boolean show = !Boolean.FALSE.equals(sequence.pendingCandidate.config().getShowInReportIfIncomplete());
        StageWindow stage = new StageWindow(
                sequence.pendingCandidate.config().getName(),
                sequence.pendingCandidate.config().getLabel(),
                StageType.TRANSITIONAL,
                sequence.pendingCandidate.timeIn(),
                null,
                false,
                false,
                show);
        stage.setLastSeenAt(sequence.pendingCandidate.materializeAt());
        sequence.record.addStage(stage);
        sequence.activeStage = ActiveStage.transitional(stage, sequence.pendingCandidate.config());
        sequence.activeStage.lastSeenAt = sequence.pendingCandidate.materializeAt();
        sequence.pendingCandidate = null;
    }

    private void closeSingleByTimeout(ActiveSequence sequence, AppConfig config) {
        if (sequence.activeStage == null || sequence.activeStage.type != StageType.SINGLE_CAMERA || sequence.activeStage.singleConfig == null) {
            return;
        }
        LocalDateTime stageEnd = sequence.activeStage.lastSeenAt;
        sequence.activeStage.window.setTimeOut(stageEnd);
        String stageName = sequence.activeStage.window.stageName();
        clearActiveStage(sequence);
        maybeCreateTransitionalCandidate(sequence, stageEnd.plusSeconds(1), stageEnd, stageName, true, config);
    }

    private void closeTransitionalByTimeout(ActiveSequence sequence, LocalDateTime closedAt) {
        if (sequence.activeStage == null || sequence.activeStage.type != StageType.TRANSITIONAL) {
            return;
        }
        if (Boolean.FALSE.equals(sequence.activeStage.transitionalConfig.getShowInReportIfIncomplete())) {
            sequence.record.getStages().remove(sequence.activeStage.window);
        }
        clearActiveStage(sequence);
        cancelPendingCandidate(sequence);
    }

    private void finalizeActiveStateForSequenceClose(ActiveSequence sequence) {
        cancelPendingCandidate(sequence);
        if (sequence.activeStage == null) {
            return;
        }
        if (sequence.activeStage.type == StageType.TRANSITIONAL
                && Boolean.FALSE.equals(sequence.activeStage.transitionalConfig.getShowInReportIfIncomplete())) {
            sequence.record.getStages().remove(sequence.activeStage.window);
        } else if (sequence.activeStage.type == StageType.REAL) {
            if (sequence.activeStage.window.timeIn() != null) {
                sequence.activeStage.window.setTimeOut(null);
            }
        } else if (sequence.activeStage.type == StageType.SINGLE_CAMERA) {
            sequence.activeStage.window.setTimeOut(null);
        }
        clearActiveStage(sequence);
    }

    private void closeActiveForNextStage(ActiveSequence sequence, LocalDateTime nextStageTimeIn) {
        if (sequence.activeStage == null || nextStageTimeIn == null) {
            return;
        }
        LocalDateTime boundary = nextStageTimeIn.minusSeconds(1);
        StageWindow stage = sequence.activeStage.window;
        LocalDateTime minBoundary = stage.timeIn() != null ? stage.timeIn() : nextStageTimeIn;
        if (boundary.isBefore(minBoundary)) {
            boundary = minBoundary;
        }
        if (sequence.activeStage.type == StageType.REAL) {
            if (stage.timeOut() == null) {
                stage.setTimeOut(boundary);
            }
        } else {
            stage.setTimeOut(boundary);
        }
        clearActiveStage(sequence);
    }

    private void cancelPendingCandidate(ActiveSequence sequence) {
        sequence.pendingCandidate = null;
    }

    private void clearActiveStage(ActiveSequence sequence) {
        sequence.activeStage = null;
    }

    private LocalDateTime singleTimeoutAt(ActiveSequence sequence) {
        if (sequence.activeStage == null || sequence.activeStage.type != StageType.SINGLE_CAMERA || sequence.activeStage.singleConfig == null || sequence.activeStage.lastSeenAt == null) {
            return null;
        }
        return sequence.activeStage.lastSeenAt.plusSeconds(Math.max(0, sequence.activeStage.singleConfig.getTimeoutSeconds()));
    }

    private LocalDateTime transitionalCloseAt(ActiveSequence sequence) {
        if (sequence.activeStage == null || sequence.activeStage.type != StageType.TRANSITIONAL || sequence.activeStage.transitionalConfig == null) {
            return null;
        }
        Integer override = sequence.activeStage.transitionalConfig.getSequenceCloseTimeoutOverrideSeconds();
        if (override == null) {
            return null;
        }
        LocalDateTime base = sequence.activeStage.lastSeenAt != null ? sequence.activeStage.lastSeenAt : sequence.activeStage.window.timeIn();
        return base == null ? null : base.plusSeconds(Math.max(0, override));
    }

    private LocalDateTime sequenceTimeoutAt(ActiveSequence sequence, AppConfig config) {
        if (sequence.lastDetectionAt == null) {
            return null;
        }
        int minutes = config.getSequenceCloseTimeoutMinutes() == null ? 0 : Math.max(0, config.getSequenceCloseTimeoutMinutes());
        return sequence.lastDetectionAt.plusSeconds(Math.max(1, minutes * 60L));
    }

    private boolean transitionalAllowedFromCurrentContext(ActiveSequence sequence, AppConfig.TransitionalStageConfig config) {
        if (config.getAllowedAfter() == null || config.getAllowedAfter().isEmpty()) {
            return true;
        }
        String currentStage = currentContextStageName(sequence);
        return currentStage != null && config.getAllowedAfter().contains(currentStage);
    }

    private String currentContextStageName(ActiveSequence sequence) {
        if (sequence.activeStage != null) {
            return sequence.activeStage.window.stageName();
        }
        for (int index = sequence.record.getStages().size() - 1; index >= 0; index--) {
            StageWindow stage = sequence.record.getStages().get(index);
            if (!stage.partial()) {
                return stage.stageName();
            }
        }
        return null;
    }

    private void finishRecord(ActiveSequence sequence, LocalDateTime finishedAt, boolean closed) {
        if (sequence.record.getStages().isEmpty()) {
            return;
        }
        sequence.record.setClosed(closed);
        sequence.record.setFinishedAt(closed ? finishedAt : null);
    }

    private void setStageOutIfEarlier(StageWindow stage, LocalDateTime boundary) {
        if (stage == null || boundary == null) {
            return;
        }
        LocalDateTime minBoundary = stage.timeIn() != null ? stage.timeIn() : boundary;
        if (boundary.isBefore(minBoundary)) {
            boundary = minBoundary;
        }
        if (stage.timeOut() == null || stage.timeOut().isAfter(boundary)) {
            stage.setTimeOut(boundary);
        }
    }

    private LocalDateTime earliest(LocalDateTime... values) {
        LocalDateTime earliest = null;
        for (LocalDateTime value : values) {
            if (value == null) {
                continue;
            }
            if (earliest == null || value.isBefore(earliest)) {
                earliest = value;
            }
        }
        return earliest;
    }

    private boolean sameMoment(LocalDateTime left, LocalDateTime right) {
        return left != null && right != null && left.equals(right);
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
                                    LocalDateTime materializeAt,
                                    String sourceKey) {}

    private static final class ActiveSequence {
        private final SequenceRecord record;
        private ActiveStage activeStage;
        private PendingCandidate pendingCandidate;
        private LocalDateTime lastDetectionAt;
        private LocalDateTime closedAt;

        private ActiveSequence(SequenceRecord record) {
            this.record = record;
        }
    }

    private static final class ActiveStage {
        private final StageWindow window;
        private final StageType type;
        private final AppConfig.RealStageConfig realConfig;
        private final AppConfig.SingleCameraStageConfig singleConfig;
        private final AppConfig.TransitionalStageConfig transitionalConfig;
        private LocalDateTime stickyOutAt;
        private LocalDateTime lastSeenAt;

        private ActiveStage(StageWindow window,
                            StageType type,
                            AppConfig.RealStageConfig realConfig,
                            AppConfig.SingleCameraStageConfig singleConfig,
                            AppConfig.TransitionalStageConfig transitionalConfig) {
            this.window = window;
            this.type = type;
            this.realConfig = realConfig;
            this.singleConfig = singleConfig;
            this.transitionalConfig = transitionalConfig;
        }

        private static ActiveStage real(StageWindow window, AppConfig.RealStageConfig config) {
            return new ActiveStage(window, StageType.REAL, config, null, null);
        }

        private static ActiveStage single(StageWindow window, AppConfig.SingleCameraStageConfig config, LocalDateTime lastSeenAt) {
            ActiveStage stage = new ActiveStage(window, StageType.SINGLE_CAMERA, null, config, null);
            stage.lastSeenAt = lastSeenAt;
            return stage;
        }

        private static ActiveStage transitional(StageWindow window, AppConfig.TransitionalStageConfig config) {
            return new ActiveStage(window, StageType.TRANSITIONAL, null, null, config);
        }
    }
}
