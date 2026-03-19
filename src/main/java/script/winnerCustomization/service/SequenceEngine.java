package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(SequenceEngine.class);
    private static final Duration SEQUENCE_GAP_TIMEOUT = Duration.ofHours(48);

    public List<SequenceRecord> build(List<Detection> detections, AppConfig config) {
        log.info("Sequence build started for {} detections", detections.size());
        Map<String, ActiveSequence> activeByPlate = new HashMap<>();
        Map<String, LocalDateTime> lastNormalizedAtByPlate = new HashMap<>();
        List<SequenceRecord> done = new ArrayList<>();

        List<Detection> sorted = detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();

        for (Detection detection : sorted) {
            CameraMatch cameraMatch = resolveCameraMatch(detection, config);
            if (cameraMatch.type() == CameraType.OTHER) {
                continue;
            }

            LocalDateTime eventTime = normalizeTimestamp(detection.plateNumber(), detection.createdAt(), lastNormalizedAtByPlate);
            ActiveSequence current = activeByPlate.get(detection.plateNumber());

            if (current != null && shouldCloseBySequenceGap(current, eventTime)) {
                addIfNotEmpty(done, finalizeSequence(current, config));
                activeByPlate.remove(detection.plateNumber());
                current = null;
            }

            if (current != null && current.hasPendingCandidate()) {
                CandidateDisposition disposition = handleCandidateBeforeEvent(current, eventTime, config);
                if (disposition == CandidateDisposition.CLOSE_SEQUENCE) {
                    addIfNotEmpty(done, finalizeSequence(current, config));
                    activeByPlate.remove(detection.plateNumber());
                    current = null;
                }
            }

            if (current == null) {
                current = new ActiveSequence(detection.plateNumber(), eventTime);
                activeByPlate.put(detection.plateNumber(), current);
            }

            applyEvent(current, cameraMatch, eventTime);
            current.lastEventAt = eventTime;
        }

        for (ActiveSequence active : activeByPlate.values()) {
            addIfNotEmpty(done, finalizeSequence(active, config));
        }

        done.sort(Comparator.comparing(SequenceRecord::getStartedAt)
                .thenComparing(SequenceRecord::getPlateNumber));
        return done;
    }

    private LocalDateTime normalizeTimestamp(String plateNumber,
                                             LocalDateTime createdAt,
                                             Map<String, LocalDateTime> lastNormalizedAtByPlate) {
        LocalDateTime last = lastNormalizedAtByPlate.get(plateNumber);
        LocalDateTime normalized = createdAt;
        if (last != null && !normalized.isAfter(last)) {
            normalized = last.plusSeconds(1);
        }
        lastNormalizedAtByPlate.put(plateNumber, normalized);
        return normalized;
    }

    private boolean shouldCloseBySequenceGap(ActiveSequence current, LocalDateTime eventTime) {
        return current.lastEventAt != null && Duration.between(current.lastEventAt, eventTime).compareTo(SEQUENCE_GAP_TIMEOUT) > 0;
    }

    private CandidateDisposition handleCandidateBeforeEvent(ActiveSequence current,
                                                            LocalDateTime eventTime,
                                                            AppConfig config) {
        Duration gap = Duration.between(current.candidate.triggerAt(), eventTime);
        if (gap.toMinutes() >= config.getTiming().getTestDriveResetMinutes()) {
            current.candidate = null;
            current.record.setFinishedAt(current.lastEventAt);
            return CandidateDisposition.CLOSE_SEQUENCE;
        }
        if (gap.toMinutes() >= config.getTiming().getTestDriveStartMinutes()) {
            current.record.addStage(new StageWindow(
                    StageType.TEST_DRIVE,
                    current.candidate.triggerAt(),
                    eventTime.minusSeconds(1),
                    null,
                    "",
                    false,
                    current.nextEventOrder()
            ));
        }
        current.candidate = null;
        return CandidateDisposition.CONTINUE;
    }

    private SequenceRecord finalizeSequence(ActiveSequence current, AppConfig config) {
        current.record.setFinishedAt(current.lastEventAt);
        annotateAlerts(current.record, config);
        return current.record;
    }

    private void addIfNotEmpty(List<SequenceRecord> done, SequenceRecord record) {
        if (!record.getStages().isEmpty()) {
            done.add(record);
        }
    }

    private void annotateAlerts(SequenceRecord record, AppConfig config) {
        List<StageWindow> updated = new ArrayList<>();
        for (StageWindow stage : record.getStages()) {
            String alert = "";
            if (!stage.partial() && stage.timeIn() != null) {
                if (stage.stageType() == StageType.DRIVE_IN) {
                    alert = thresholdAlert(stage, record.getFinishedAt(),
                            config.getTiming().getDriveInToDriveOutAlertMinutes(),
                            "No Drive in (out) within " + config.getTiming().getDriveInToDriveOutAlertMinutes() + " minutes");
                } else if (stage.stageType() == StageType.SERVICE) {
                    alert = thresholdAlert(stage, record.getFinishedAt(),
                            config.getTiming().getServiceToPostAlertMinutes(),
                            "No Post in within " + config.getTiming().getServiceToPostAlertMinutes() + " minutes");
                }
            }
            updated.add(stage.withAlert(alert));
        }
        record.getStages().clear();
        record.getStages().addAll(updated);
    }

    private String thresholdAlert(StageWindow stage, LocalDateTime sequenceFinishedAt, int thresholdMinutes, String message) {
        LocalDateTime border = stage.timeOut() != null ? stage.timeOut() : sequenceFinishedAt;
        if (border == null) {
            return "";
        }
        return Duration.between(stage.timeIn(), border).toMinutes() >= thresholdMinutes ? message : "";
    }

    private void applyEvent(ActiveSequence current, CameraMatch cameraMatch, LocalDateTime eventTime) {
        if (handleStickyPostTransition(current, cameraMatch, eventTime)) {
            return;
        }
        switch (cameraMatch.type()) {
            case DRIVE_IN_IN -> handleDriveInIn(current, eventTime);
            case DRIVE_IN_OUT -> handleDriveInOut(current, eventTime);
            case SERVICE_IN -> handleServiceIn(current, eventTime);
            case SERVICE_OUT -> handleServiceOut(current, eventTime);
            case POST_IN -> handlePostIn(current, eventTime, cameraMatch.reportLabel());
            case POST_OUT -> handlePostOut(current, eventTime, cameraMatch.reportLabel());
            case PARKING_IN -> handleParkingIn(current, eventTime);
            case PARKING_OUT -> handleParkingOut(current, eventTime);
            case DRIVE_IN_TO_SERVICE -> handleDriveInToService(current, eventTime);
            case SERVICE_TO_DRIVE_IN -> handleServiceToDriveIn(current, eventTime);
            case OTHER -> {
            }
        }
    }

    private boolean handleStickyPostTransition(ActiveSequence current, CameraMatch cameraMatch, LocalDateTime eventTime) {
        if (current.activeStageType() != StageType.POST) {
            return false;
        }
        if (cameraMatch.type() == CameraType.POST_IN
                && Objects.equals(current.activeStageLabel(), cameraMatch.reportLabel())) {
            return false;
        }
        if (cameraMatch.type() == CameraType.POST_OUT
                && Objects.equals(current.activeStageLabel(), cameraMatch.reportLabel())) {
            return false;
        }

        LocalDateTime postOutTime = closeStickyPostAt(current, eventTime);
        if (cameraMatch.type() == CameraType.SERVICE_OUT) {
            addSyntheticServiceStage(current, postOutTime.plusSeconds(1), eventTime);
            openStage(current, StageType.BACKYARD, eventTime, false);
            current.record.addPathStep("Service (out)");
            return true;
        }
        if (cameraMatch.type() != CameraType.SERVICE_IN && current.postOutCandidateAt != null) {
            addSyntheticServiceStage(current, postOutTime.plusSeconds(1), eventTime.minusSeconds(1));
        }
        current.postOutCandidateAt = null;
        return false;
    }

    private void handleDriveInIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.DRIVE_IN) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.postOutCandidateAt = null;
        openStage(current, StageType.DRIVE_IN, eventTime, false);
        current.record.addPathStep("Drive in (in)");
    }

    private void handleDriveInOut(ActiveSequence current, LocalDateTime eventTime) {
        if (current.hasPendingCandidate() && current.lastEventType == CameraType.DRIVE_IN_OUT && current.activeStageIndex == null) {
            return;
        }
        if (current.activeStageType() == StageType.DRIVE_IN) {
            closeActiveAt(current, eventTime);
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.DRIVE_IN, null, eventTime, null, "", true, current.nextEventOrder()));
        }
        current.record.addPathStep("Drive in (out)");
        createCandidateIfAbsent(current, eventTime);
        current.lastEventType = CameraType.DRIVE_IN_OUT;
    }

    private void handleServiceIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.SERVICE) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.postOutCandidateAt = null;
        openStage(current, StageType.SERVICE, eventTime, false);
        current.record.addPathStep("Service (in)");
    }

    private void handleServiceOut(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.BACKYARD) {
            return;
        }
        if (current.activeStageType() == StageType.SERVICE) {
            closeActiveAt(current, eventTime.minusSeconds(1));
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.SERVICE, null, eventTime, null, "", true, current.nextEventOrder()));
        }
        current.postOutCandidateAt = null;
        openStage(current, StageType.BACKYARD, eventTime, false);
        current.record.addPathStep("Service (out)");
    }

    private void handlePostIn(ActiveSequence current, LocalDateTime eventTime, String postLabel) {
        if (current.activeStageType() == StageType.POST
                && Objects.equals(current.activeStageLabel(), postLabel)) {
            return;
        }
        if (current.activeStageType() == StageType.SERVICE) {
            closeActiveAt(current, eventTime.minusSeconds(1));
        } else if (current.latestStageType() == StageType.SERVICE
                && Objects.equals(current.latestStageTimeOut(), eventTime.minusSeconds(1))) {
            // synthetic/previous service already ends right before this Post start
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.SERVICE, null, eventTime.minusSeconds(1), null, "", true, current.nextEventOrder()));
        }
        openStage(current, StageType.POST, eventTime, false, postLabel);
        current.postOutCandidateAt = null;
        current.record.addPathStep("Post (in)");
    }

    private void handlePostOut(ActiveSequence current, LocalDateTime eventTime, String postLabel) {
        if (current.activeStageType() == StageType.POST
                && Objects.equals(current.activeStageLabel(), postLabel)) {
            current.postOutCandidateAt = eventTime;
            if (current.lastEventType != CameraType.POST_OUT) {
                current.record.addPathStep("Post (out)");
            }
            current.lastEventType = CameraType.POST_OUT;
            return;
        }

        if (current.activeStageType() == StageType.POST) {
            closeStickyPostAt(current, eventTime);
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.POST, null, eventTime, postLabel, "", true, current.nextEventOrder()));
        }

        openStage(current, StageType.SERVICE, eventTime, false, null);
        current.postOutCandidateAt = null;
        current.record.addPathStep("Post (out)");
        current.lastEventType = CameraType.POST_OUT;
    }

    private void handleParkingIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.PARKING) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.postOutCandidateAt = null;
        openStage(current, StageType.PARKING, eventTime, false);
        current.record.addPathStep("Parking (in)");
    }

    private void handleParkingOut(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.BACKYARD) {
            return;
        }
        if (current.activeStageType() == StageType.PARKING) {
            closeActiveAt(current, eventTime.minusSeconds(1));
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.PARKING, null, eventTime, null, "", true, current.nextEventOrder()));
        }
        current.postOutCandidateAt = null;
        openStage(current, StageType.BACKYARD, eventTime, false);
        current.record.addPathStep("Parking (out)");
    }

    private void handleDriveInToService(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.BACKYARD) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.postOutCandidateAt = null;
        openStage(current, StageType.BACKYARD, eventTime, false);
        current.record.addPathStep("Drive-In -> Service");
    }

    private void handleServiceToDriveIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageIndex != null) {
            closeActiveAt(current, eventTime.minusSeconds(1));
        }
        current.postOutCandidateAt = null;
        current.record.addPathStep("Service -> Drive-In");
        createCandidateIfAbsent(current, eventTime);
        current.lastEventType = CameraType.SERVICE_TO_DRIVE_IN;
    }

    private LocalDateTime closeStickyPostAt(ActiveSequence current, LocalDateTime nextEventTime) {
        if (current.activeStageIndex == null || current.activeStageType() != StageType.POST) {
            return nextEventTime.minusSeconds(1);
        }
        LocalDateTime postOutTime = current.postOutCandidateAt != null
                ? current.postOutCandidateAt
                : nextEventTime.minusSeconds(1);
        StageWindow active = current.record.getStages().get(current.activeStageIndex);
        current.record.getStages().set(current.activeStageIndex, active.withTimeOut(postOutTime));
        current.activeStageIndex = null;
        return postOutTime;
    }

    private void addSyntheticServiceStage(ActiveSequence current, LocalDateTime timeIn, LocalDateTime timeOut) {
        if (timeIn == null || timeOut == null || timeIn.isAfter(timeOut)) {
            return;
        }
        current.record.addStage(new StageWindow(StageType.SERVICE, timeIn, timeOut, null, "", false, current.nextEventOrder()));
    }

    private void createCandidateIfAbsent(ActiveSequence current, LocalDateTime eventTime) {
        if (current.candidate == null) {
            current.candidate = new TestDriveCandidate(eventTime);
        }
    }

    private void openStage(ActiveSequence current, StageType type, LocalDateTime timeIn, boolean partial, String reportLabelOverride) {
        current.record.addStage(new StageWindow(type, timeIn, null, reportLabelOverride, "", partial, current.nextEventOrder()));
        current.activeStageIndex = current.record.getStages().size() - 1;
        current.lastEventType = null;
    }

    private void openStage(ActiveSequence current, StageType type, LocalDateTime timeIn, boolean partial) {
        openStage(current, type, timeIn, partial, null);
    }

    private void closeActiveAt(ActiveSequence current, LocalDateTime timeOut) {
        if (current.activeStageIndex == null) {
            return;
        }
        Integer closingStageIndex = current.activeStageIndex;
        StageWindow active = current.record.getStages().get(closingStageIndex);
        if (active.timeOut() == null) {
            current.record.getStages().set(closingStageIndex, active.withTimeOut(timeOut));
        }
        current.activeStageIndex = null;
    }

    private CameraMatch resolveCameraMatch(Detection detection, AppConfig config) {
        AppConfig.CamerasConfig cameras = config.getCameras();
        if (matchesAny(cameras.getDriveInIn(), detection)) return new CameraMatch(CameraType.DRIVE_IN_IN, null);
        if (matchesAny(cameras.getDriveInOut(), detection)) return new CameraMatch(CameraType.DRIVE_IN_OUT, null);
        if (matchesAny(cameras.getDriveInToService(), detection)) return new CameraMatch(CameraType.DRIVE_IN_TO_SERVICE, null);
        if (matchesAny(cameras.getServiceIn(), detection)) return new CameraMatch(CameraType.SERVICE_IN, null);
        if (matchesAny(cameras.getServiceOut(), detection)) return new CameraMatch(CameraType.SERVICE_OUT, null);
        if (matchesAny(cameras.getServiceToDriveIn(), detection)) return new CameraMatch(CameraType.SERVICE_TO_DRIVE_IN, null);
        if (matchesAny(cameras.getParkingIn(), detection)) return new CameraMatch(CameraType.PARKING_IN, null);
        if (matchesAny(cameras.getParkingOut(), detection)) return new CameraMatch(CameraType.PARKING_OUT, null);

        for (AppConfig.PostCameraConfig post : cameras.getServicePosts()) {
            if (post.matchesIn(detection)) return new CameraMatch(CameraType.POST_IN, post.getPostName());
            if (post.matchesOut(detection)) return new CameraMatch(CameraType.POST_OUT, post.getPostName());
        }
        return new CameraMatch(CameraType.OTHER, null);
    }

    private boolean matchesAny(List<AppConfig.CameraConfig> cameras, Detection detection) {
        if (cameras == null) {
            return false;
        }
        return cameras.stream().anyMatch(camera -> camera != null
                && camera.getAnalyticsId() == detection.analyticsId()
                && camera.matchesDirection(detection.direction()));
    }

    enum CameraType {
        DRIVE_IN_IN,
        DRIVE_IN_OUT,
        DRIVE_IN_TO_SERVICE,
        SERVICE_IN,
        POST_IN,
        POST_OUT,
        SERVICE_OUT,
        SERVICE_TO_DRIVE_IN,
        PARKING_IN,
        PARKING_OUT,
        OTHER
    }

    private enum CandidateDisposition {
        CONTINUE,
        CLOSE_SEQUENCE
    }

    private record CameraMatch(CameraType type, String reportLabel) {
    }

    private record TestDriveCandidate(LocalDateTime triggerAt) {
    }

    private static final class ActiveSequence {
        private final SequenceRecord record;
        private final LocalDateTime startedAt;
        private LocalDateTime lastEventAt;
        private Integer activeStageIndex;
        private int eventOrder;
        private TestDriveCandidate candidate;
        private LocalDateTime postOutCandidateAt;
        private CameraType lastEventType;

        private ActiveSequence(String plateNumber, LocalDateTime startedAt) {
            this.record = new SequenceRecord(plateNumber, startedAt);
            this.startedAt = startedAt;
            this.lastEventAt = startedAt;
        }

        private int nextEventOrder() {
            return ++eventOrder;
        }

        private StageType activeStageType() {
            if (activeStageIndex == null) {
                return null;
            }
            return record.getStages().get(activeStageIndex).stageType();
        }

        private String activeStageLabel() {
            if (activeStageIndex == null) {
                return null;
            }
            return record.getStages().get(activeStageIndex).reportLabel();
        }

        private boolean hasPendingCandidate() {
            return candidate != null;
        }

        private StageType latestStageType() {
            if (record.getStages().isEmpty()) {
                return null;
            }
            return record.getStages().getLast().stageType();
        }

        private LocalDateTime latestStageTimeOut() {
            if (record.getStages().isEmpty()) {
                return null;
            }
            return record.getStages().getLast().timeOut();
        }
    }
}
