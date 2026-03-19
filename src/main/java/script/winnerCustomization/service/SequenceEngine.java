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
            CameraType type = resolveCameraType(detection, config);
            if (type == CameraType.OTHER) {
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

            applyEvent(current, type, eventTime);
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

    private void applyEvent(ActiveSequence current, CameraType type, LocalDateTime eventTime) {
        switch (type) {
            case DRIVE_IN_IN -> handleDriveInIn(current, eventTime);
            case DRIVE_IN_OUT -> handleDriveInOut(current, eventTime);
            case SERVICE_IN -> handleServiceIn(current, eventTime);
            case SERVICE_OUT -> handleServiceOut(current, eventTime);
            case POST_IN -> handlePostIn(current, eventTime);
            case POST_OUT -> handlePostOut(current, eventTime);
            case PARKING_IN -> handleParkingIn(current, eventTime);
            case PARKING_OUT -> handleParkingOut(current, eventTime);
            case DRIVE_IN_TO_SERVICE -> handleDriveInToService(current, eventTime);
            case SERVICE_TO_DRIVE_IN -> handleServiceToDriveIn(current, eventTime);
            case OTHER -> {
            }
        }
    }

    private void handleDriveInIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.DRIVE_IN) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
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
            current.record.addStage(new StageWindow(StageType.DRIVE_IN, null, eventTime, "", true, current.nextEventOrder()));
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
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
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
            current.record.addStage(new StageWindow(StageType.SERVICE, null, eventTime, "", true, current.nextEventOrder()));
        }
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
        openStage(current, StageType.BACKYARD, eventTime, false);
        current.record.addPathStep("Service (out)");
    }

    private void handlePostIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.POST) {
            return;
        }
        if (current.activeStageType() == StageType.SERVICE) {
            closeActiveAt(current, eventTime.minusSeconds(1));
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.SERVICE, null, eventTime.minusSeconds(1), "", true, current.nextEventOrder()));
        }
        openStage(current, StageType.POST, eventTime, false);
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = current.activeStageIndex;
        current.record.addPathStep("Post (in)");
    }

    private void handlePostOut(ActiveSequence current, LocalDateTime eventTime) {
        if (current.canOverwritePostOut()) {
            overwritePostOut(current, eventTime);
            current.record.addPathStep("Post (out)");
            current.lastEventType = CameraType.POST_OUT;
            return;
        }

        if (current.activeStageType() == StageType.POST) {
            closeActiveAt(current, eventTime);
        } else {
            closeActiveAt(current, eventTime.minusSeconds(1));
            current.record.addStage(new StageWindow(StageType.POST, null, eventTime, "", true, current.nextEventOrder()));
            current.postAwaitingOverwriteStageIndex = current.record.getStages().size() - 1;
        }

        openStage(current, StageType.SERVICE, eventTime, false);
        current.serviceOpenedByPostOutStageIndex = current.activeStageIndex;
        current.record.addPathStep("Post (out)");
        current.lastEventType = CameraType.POST_OUT;
    }

    private void overwritePostOut(ActiveSequence current, LocalDateTime eventTime) {
        StageWindow post = current.record.getStages().get(current.postAwaitingOverwriteStageIndex);
        current.record.getStages().set(current.postAwaitingOverwriteStageIndex, post.withTimeOut(eventTime));
        StageWindow service = current.record.getStages().get(current.serviceOpenedByPostOutStageIndex);
        current.record.getStages().set(current.serviceOpenedByPostOutStageIndex, service.withTimeIn(eventTime));
        current.lastEventAt = eventTime;
    }

    private void handleParkingIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.PARKING) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
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
            current.record.addStage(new StageWindow(StageType.PARKING, null, eventTime, "", true, current.nextEventOrder()));
        }
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
        openStage(current, StageType.BACKYARD, eventTime, false);
        current.record.addPathStep("Parking (out)");
    }

    private void handleDriveInToService(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageType() == StageType.BACKYARD) {
            return;
        }
        closeActiveAt(current, eventTime.minusSeconds(1));
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
        openStage(current, StageType.BACKYARD, eventTime, false);
        current.record.addPathStep("Drive-In -> Service");
    }

    private void handleServiceToDriveIn(ActiveSequence current, LocalDateTime eventTime) {
        if (current.activeStageIndex != null) {
            closeActiveAt(current, eventTime.minusSeconds(1));
        }
        current.serviceOpenedByPostOutStageIndex = null;
        current.postAwaitingOverwriteStageIndex = null;
        current.record.addPathStep("Service -> Drive-In");
        createCandidateIfAbsent(current, eventTime);
        current.lastEventType = CameraType.SERVICE_TO_DRIVE_IN;
    }

    private void createCandidateIfAbsent(ActiveSequence current, LocalDateTime eventTime) {
        if (current.candidate == null) {
            current.candidate = new TestDriveCandidate(eventTime);
        }
    }

    private void openStage(ActiveSequence current, StageType type, LocalDateTime timeIn, boolean partial) {
        current.record.addStage(new StageWindow(type, timeIn, null, "", partial, current.nextEventOrder()));
        current.activeStageIndex = current.record.getStages().size() - 1;
        current.lastEventType = null;
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
        if (closingStageIndex.equals(current.serviceOpenedByPostOutStageIndex)) {
            current.serviceOpenedByPostOutStageIndex = null;
            current.postAwaitingOverwriteStageIndex = null;
        }
    }

    private CameraType resolveCameraType(Detection detection, AppConfig config) {
        AppConfig.CamerasConfig cameras = config.getCameras();
        if (matchesAny(cameras.getDriveInIn(), detection)) return CameraType.DRIVE_IN_IN;
        if (matchesAny(cameras.getDriveInOut(), detection)) return CameraType.DRIVE_IN_OUT;
        if (matchesAny(cameras.getDriveInToService(), detection)) return CameraType.DRIVE_IN_TO_SERVICE;
        if (matchesAny(cameras.getServiceIn(), detection)) return CameraType.SERVICE_IN;
        if (matchesAny(cameras.getServiceOut(), detection)) return CameraType.SERVICE_OUT;
        if (matchesAny(cameras.getServiceToDriveIn(), detection)) return CameraType.SERVICE_TO_DRIVE_IN;
        if (matchesAny(cameras.getParkingIn(), detection)) return CameraType.PARKING_IN;
        if (matchesAny(cameras.getParkingOut(), detection)) return CameraType.PARKING_OUT;

        for (AppConfig.PostCameraConfig post : cameras.getServicePosts()) {
            if (post.matchesIn(detection)) return CameraType.POST_IN;
            if (post.matchesOut(detection)) return CameraType.POST_OUT;
        }
        return CameraType.OTHER;
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

    private record TestDriveCandidate(LocalDateTime triggerAt) {
    }

    private static final class ActiveSequence {
        private final SequenceRecord record;
        private final LocalDateTime startedAt;
        private LocalDateTime lastEventAt;
        private Integer activeStageIndex;
        private int eventOrder;
        private TestDriveCandidate candidate;
        private Integer postAwaitingOverwriteStageIndex;
        private Integer serviceOpenedByPostOutStageIndex;
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

        private boolean hasPendingCandidate() {
            return candidate != null;
        }

        private boolean canOverwritePostOut() {
            return postAwaitingOverwriteStageIndex != null
                    && serviceOpenedByPostOutStageIndex != null
                    && activeStageIndex != null
                    && activeStageIndex.equals(serviceOpenedByPostOutStageIndex)
                    && activeStageType() == StageType.SERVICE;
        }
    }
}
