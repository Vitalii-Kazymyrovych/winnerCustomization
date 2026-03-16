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
            if (type == CameraType.OTHER) {
                log.debug("Skipping detection id={} plate={} because camera is not mapped", detection.id(), detection.plateNumber());
                continue;
            }
            SequenceRecord current = active.get(detection.plateNumber());
            if (current == null || shouldResetAsNew(current, detection, config)) {
                if (current != null) {
                    log.info("Resetting active sequence for plate={} at {}", detection.plateNumber(), detection.createdAt());
                    current.setFinishedAt(detection.createdAt());
                    done.add(current);
                }
                if (type != CameraType.DRIVE_IN_IN && type != CameraType.SERVICE_DRIVE_IN_IN) {
                    log.debug("Ignoring detection id={} plate={} because sequence has not started yet and camera type={} is not a start event",
                            detection.id(), detection.plateNumber(), type);
                    continue;
                }
                current = new SequenceRecord(detection.plateNumber(), detection.createdAt());
                active.put(detection.plateNumber(), current);
                log.info("Started new sequence for plate={} at {}", detection.plateNumber(), detection.createdAt());
            }

            applyEvent(current, type, detection.createdAt());
            evaluateAlerts(current, detection.createdAt(), config);

            if (type == CameraType.PARKING_OUT || type == CameraType.DRIVE_IN_OUT_FINAL) {
                current.setFinishedAt(detection.createdAt());
                done.add(current);
                active.remove(detection.plateNumber());
                log.info("Closed sequence for plate={} at {} by event={}", detection.plateNumber(), detection.createdAt(), type);
            }
        }

        done.addAll(active.values());
        done.sort(Comparator.comparing(SequenceRecord::getStartedAt));
        log.info("Sequence build finished. doneRecords={}, stillActive={}", done.size(), active.size());
        return done;
    }

    private boolean shouldResetAsNew(SequenceRecord current, Detection detection, AppConfig config) {
        LocalDateTime edge = current.getServiceOutAt() != null ? current.getServiceOutAt() : current.getDriveInOutAt();
        if (edge == null || current.getParkingInAt() != null || current.getServiceInAt() != null) {
            return false;
        }
        long mins = Duration.between(edge, detection.createdAt()).toMinutes();
        return mins >= config.getTiming().getTestDriveResetMinutes();
    }

    private void applyEvent(SequenceRecord current, CameraType type, LocalDateTime at) {
        log.debug("Applying event {} at {} for plate={}", type, at, current.getPlateNumber());
        switch (type) {
            case DRIVE_IN_OUT -> current.setDriveInOutAt(at);
            case SERVICE_IN -> current.setServiceInAt(at);
            case POST_IN -> current.setPostInAt(at);
            case SERVICE_OUT -> current.setServiceOutAt(at);
            case PARKING_IN -> current.setParkingInAt(at);
            case PARKING_OUT -> current.setParkingOutAt(at);
            case SERVICE_DRIVE_IN_IN -> { }
            case DRIVE_IN_IN -> { }
            default -> { }
        }
    }

    private void evaluateAlerts(SequenceRecord r, LocalDateTime now, AppConfig config) {
        if (r.getDriveInOutAt() == null && Duration.between(r.getStartedAt(), now).toMinutes() >= config.getTiming().getDriveInToDriveOutAlertMinutes()) {
            String alert = "No Drive in (out) within " + config.getTiming().getDriveInToDriveOutAlertMinutes() + " minutes";
            log.info("Alert triggered for plate={}: {}", r.getPlateNumber(), alert);
            r.addAlert(alert);
        }
        if (r.getServiceInAt() != null && r.getPostInAt() == null && Duration.between(r.getServiceInAt(), now).toMinutes() >= config.getTiming().getServiceToPostAlertMinutes()) {
            String alert = "No Service post (in) within " + config.getTiming().getServiceToPostAlertMinutes() + " minutes";
            log.info("Alert triggered for plate={}: {}", r.getPlateNumber(), alert);
            r.addAlert(alert);
        }
    }

    private CameraType resolveCameraType(Detection detection, AppConfig config) {
        AppConfig.CamerasConfig cameras = config.getCameras();
        if (matches(cameras.getDriveInIn(), detection)) return CameraType.DRIVE_IN_IN;
        if (matches(cameras.getDriveInOut(), detection)) return CameraType.DRIVE_IN_OUT;
        if (matches(cameras.getServiceDriveInIn(), detection)) return CameraType.SERVICE_DRIVE_IN_IN;
        if (matches(cameras.getServiceIn(), detection)) return CameraType.SERVICE_IN;
        if (matches(cameras.getServiceOut(), detection)) return CameraType.SERVICE_OUT;
        if (matches(cameras.getParkingIn(), detection)) return CameraType.PARKING_IN;
        if (matches(cameras.getParkingOut(), detection)) return CameraType.PARKING_OUT;

        for (AppConfig.PostCameraConfig post : cameras.getServicePosts()) {
            if (matches(post.getIn(), detection)) return CameraType.POST_IN;
        }

        return CameraType.OTHER;
    }

    private boolean matches(AppConfig.CameraConfig camera, Detection detection) {
        return camera != null
                && camera.getAnalyticsId() == detection.analyticsId()
                && camera.matchesDirection(detection.direction());
    }

    enum CameraType {
        DRIVE_IN_IN,
        DRIVE_IN_OUT,
        SERVICE_DRIVE_IN_IN,
        SERVICE_IN,
        POST_IN,
        SERVICE_OUT,
        PARKING_IN,
        PARKING_OUT,
        DRIVE_IN_OUT_FINAL,
        OTHER
    }
}
