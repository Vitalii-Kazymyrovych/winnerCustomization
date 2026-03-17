package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AlertJobRecord;
import script.winnerCustomization.model.AlertJobType;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AlertSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(AlertSchedulerService.class);
    private static final int SEND_BATCH_SIZE = 100;

    private final RuntimeConfig runtimeConfig;
    private final DetectionService detectionService;
    private final SequenceEngine sequenceEngine;
    private final AlertJobStorageService alertJobStorageService;
    private final TelegramNotifier telegramNotifier;
    private final Clock clock;

    public AlertSchedulerService(RuntimeConfig runtimeConfig,
                                 DetectionService detectionService,
                                 SequenceEngine sequenceEngine,
                                 AlertJobStorageService alertJobStorageService,
                                 TelegramNotifier telegramNotifier,
                                 Clock clock) {
        this.runtimeConfig = runtimeConfig;
        this.detectionService = detectionService;
        this.sequenceEngine = sequenceEngine;
        this.alertJobStorageService = alertJobStorageService;
        this.telegramNotifier = telegramNotifier;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${alerts.sync.delay.millis:10000}")
    public void syncPendingJobs() {
        try {
            alertJobStorageService.initialize();
            AppConfig config = runtimeConfig.get();
            List<Detection> detections = detectionService.loadAllDetections();
            List<SequenceRecord> records = sequenceEngine.build(detections, config);
            for (SequenceRecord record : records) {
                upsertOrCancelDriveInAlert(record, config);
                upsertOrCancelServiceAlert(record, config);
            }
            log.debug("Alert sync completed for {} sequence records", records.size());
        } catch (Exception exception) {
            log.warn("Alert sync failed: {}", exception.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${alerts.dispatch.delay.millis:5000}")
    public void dispatchDueAlerts() {
        try {
            alertJobStorageService.initialize();
            LocalDateTime now = LocalDateTime.now(clock);
            List<AlertJobRecord> due = alertJobStorageService.findDuePending(now, SEND_BATCH_SIZE);
            AppConfig.NotificationsConfig notifications = runtimeConfig.get().getNotifications();
            for (AlertJobRecord job : due) {
                String text = "Plate " + job.plateNumber() + ": " + job.message();
                telegramNotifier.sendIfEnabled(notifications, text);
                alertJobStorageService.markSent(job.id(), now);
            }
            if (!due.isEmpty()) {
                log.info("Dispatched {} due alert jobs", due.size());
            }
        } catch (Exception exception) {
            log.warn("Alert dispatch failed: {}", exception.getMessage());
        }
    }

    private void upsertOrCancelDriveInAlert(SequenceRecord record, AppConfig config) {
        LocalDateTime triggerAt = record.getStartedAt();
        if (record.getDriveInOutAt() == null) {
            int minutes = config.getTiming().getDriveInToDriveOutAlertMinutes();
            alertJobStorageService.upsertPending(
                    record.getPlateNumber(),
                    AlertJobType.DRIVE_IN_OUT_MISSING,
                    triggerAt,
                    triggerAt.plusMinutes(minutes),
                    "No Drive in (out) within " + minutes + " minutes"
            );
            return;
        }
        alertJobStorageService.cancel(record.getPlateNumber(), AlertJobType.DRIVE_IN_OUT_MISSING, triggerAt);
    }

    private void upsertOrCancelServiceAlert(SequenceRecord record, AppConfig config) {
        LocalDateTime triggerAt = record.getServiceInAt();
        if (triggerAt == null) {
            return;
        }

        if (record.getPostInAt() == null) {
            int minutes = config.getTiming().getServiceToPostAlertMinutes();
            alertJobStorageService.upsertPending(
                    record.getPlateNumber(),
                    AlertJobType.SERVICE_POST_IN_MISSING,
                    triggerAt,
                    triggerAt.plusMinutes(minutes),
                    "No Service post (in) within " + minutes + " minutes"
            );
            return;
        }
        alertJobStorageService.cancel(record.getPlateNumber(), AlertJobType.SERVICE_POST_IN_MISSING, triggerAt);
    }
}
