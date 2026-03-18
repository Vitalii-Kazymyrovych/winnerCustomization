package script.winnerCustomization.service;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class SequenceEngine {
    private static final Logger log = LoggerFactory.getLogger(SequenceEngine.class);

    public List<SequenceRecord> build(List<Detection> detections, AppConfig config) {
        log.info("Sequence build started for {} detections", detections.size());
        Map<String, SequenceRecord> active = new HashMap<>();
        List<SequenceRecord> done = new ArrayList<>();

        for (Detection detection : detections) {
            CameraType type = resolveCameraType(detection, config);
            SequenceRecord current = active.get(detection.plateNumber());
            if (current == null || shouldResetAsNew(current, detection, config)) {
                if (current != null) {
                    finalizeOpenStages(current);
                    current.setFinishedAt(detection.createdAt());
                    done.add(current);
                }
                if (!isStartCamera(type)) {
                    continue;
                }
                current = new SequenceRecord(detection.plateNumber(), detection.createdAt());
                active.put(detection.plateNumber(), current);
            }

            handlePendingBackyard(current, type, detection.createdAt());
            applyEvent(current, type, detection.createdAt());
            evaluateAlerts(current, detection.createdAt(), config);

            if (type == CameraType.PARKING_OUT) {
                finalizeOpenStages(current);
                current.setFinishedAt(detection.createdAt());
                done.add(current);
                active.remove(detection.plateNumber());
            }
        }

        active.values().forEach(this::finalizeOpenStages);
        done.addAll(active.values());
        done.sort(Comparator.comparing(SequenceRecord::getStartedAt));
        return done;
    }

    private boolean isStartCamera(CameraType type) {
        return type == CameraType.DRIVE_IN_IN || type == CameraType.SERVICE_IN || type == CameraType.POST_OUT;
    }

    private boolean shouldResetAsNew(SequenceRecord current, Detection detection, AppConfig config) {
        LocalDateTime edge = current.getTestDriveAnchorAt();
        if (edge == null) {
            return false;
        }
        return Duration.between(edge, detection.createdAt()).toMinutes() >= config.getTiming().getTestDriveResetMinutes();
    }

    private void handlePendingBackyard(SequenceRecord current, CameraType type, LocalDateTime at) {
        LocalDateTime backyardStart = current.getPendingBackyardStartedAt();
        if (backyardStart == null) {
            return;
        }

        boolean expectedServiceIn = current.getPendingBackyardExpectation() == SequenceRecord.PendingBackyardExpectation.SERVICE_IN;
        boolean expectedServiceToDriveIn = current.getPendingBackyardExpectation() == SequenceRecord.PendingBackyardExpectation.SERVICE_TO_DRIVE_IN;
        boolean isExpected = (expectedServiceIn && type == CameraType.SERVICE_IN)
                || (expectedServiceToDriveIn && type == CameraType.SERVICE_TO_DRIVE_IN);

        if (isExpected) {
            current.clearPendingBackyard();
            return;
        }

        current.addBackyardStage(backyardStart, at);
        current.clearPendingBackyard();
    }

    private void finalizeOpenStages(SequenceRecord current) {
        if (current.getPendingBackyardStartedAt() != null) {
            current.addBackyardStage(current.getPendingBackyardStartedAt(), null);
            current.clearPendingBackyard();
        }
    }

    private void applyEvent(SequenceRecord current, CameraType type, LocalDateTime at) {
        switch (type) {
            case DRIVE_IN_OUT -> {
                if (current.getDriveInOutAt() == null) {
                    current.setDriveInOutAt(at);
                }
                current.setTestDriveAnchorAt(current.getDriveInOutAt());
            }
            case DRIVE_IN_TO_SERVICE -> {
                current.setDriveInToServiceAt(at);
                if (Objects.equals(current.getTestDriveAnchorAt(), current.getDriveInOutAt())) {
                    current.setTestDriveAnchorAt(null);
                }
                current.startPendingBackyard(at, SequenceRecord.PendingBackyardExpectation.SERVICE_IN);
            }
            case SERVICE_IN -> {
                if (current.getServiceInAt() != null) {
                    return;
                }
                current.setServiceInAt(at);
            }
            case POST_IN -> {
                if (current.getPostInAt() != null) {
                    return;
                }
                if (current.getServiceInAt() != null && current.getServiceFirstFinishedAt() == null) {
                    current.setServiceFirstFinishedAt(at);
                }
                current.setPostInAt(at);
            }
            case POST_OUT -> {
                current.setPostOutAt(at);
                current.setSecondServiceInAt(at);
                if (current.getServiceInAt() == null) {
                    current.setServiceInAt(at);
                }
                if (current.getServiceFirstFinishedAt() == null) {
                    current.setServiceFirstFinishedAt(at);
                }
            }
            case SERVICE_OUT -> {
                if (current.getPostInAt() == null) {
                    if (current.getServiceFirstFinishedAt() == null && current.getServiceInAt() != null) {
                        current.setServiceFirstFinishedAt(at);
                    }
                } else {
                    current.setServiceOutAt(at);
                }
                current.startPendingBackyard(at, SequenceRecord.PendingBackyardExpectation.SERVICE_TO_DRIVE_IN);
            }
            case SERVICE_TO_DRIVE_IN -> {
                current.setServiceToDriveInAt(at);
                current.setTestDriveAnchorAt(at);
            }
            case PARKING_IN -> {
                if (current.getParkingInAt() != null) {
                    return;
                }
                if (current.getSecondServiceInAt() != null && current.getServiceOutAt() == null) {
                    current.setServiceOutAt(at);
                } else if (current.getPostInAt() != null && current.getPostOutAt() == null) {
                    current.setPostOutAt(at);
                    current.setSecondServiceInAt(at);
                } else if (current.getServiceInAt() != null && current.getServiceFirstFinishedAt() == null) {
                    current.setServiceFirstFinishedAt(at);
                }
                current.setParkingInAt(at);
            }
            case PARKING_OUT -> current.setParkingOutAt(at);
            case DRIVE_IN_IN -> {
                if (Objects.equals(current.getTestDriveAnchorAt(), current.getServiceToDriveInAt())) {
                    current.setTestDriveAnchorAt(null);
                }
            }
            case OTHER -> { }
        }
    }

    private void evaluateAlerts(SequenceRecord r, LocalDateTime now, AppConfig config) {
        if (r.getDriveInOutAt() == null && Duration.between(r.getStartedAt(), now).toMinutes() >= config.getTiming().getDriveInToDriveOutAlertMinutes()) {
            r.addAlert("No Drive in (out) within " + config.getTiming().getDriveInToDriveOutAlertMinutes() + " minutes");
        }
        if (r.getServiceInAt() != null && r.getPostInAt() == null && Duration.between(r.getServiceInAt(), now).toMinutes() >= config.getTiming().getServiceToPostAlertMinutes()) {
            r.addAlert("No Service post (in) within " + config.getTiming().getServiceToPostAlertMinutes() + " minutes");
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
}
