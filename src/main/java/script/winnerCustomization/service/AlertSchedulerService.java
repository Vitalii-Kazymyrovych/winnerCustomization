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
import script.winnerCustomization.model.SequenceRecord.StageType;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

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
                syncDriveInAlerts(record, config);
                syncServiceAlerts(record, config);
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

    private void syncDriveInAlerts(SequenceRecord record, AppConfig config) {
        syncStageAlerts(
                record,
                StageType.DRIVE_IN,
                AlertJobType.DRIVE_IN_OUT_MISSING,
                config.getTiming().getDriveInToDriveOutAlertMinutes(),
                "No Drive in (out) within " + config.getTiming().getDriveInToDriveOutAlertMinutes() + " minutes"
        );
    }

    private void syncServiceAlerts(SequenceRecord record, AppConfig config) {
        syncStageAlerts(
                record,
                StageType.SERVICE,
                AlertJobType.SERVICE_POST_IN_MISSING,
                config.getTiming().getServiceToPostAlertMinutes(),
                "No Post in within " + config.getTiming().getServiceToPostAlertMinutes() + " minutes"
        );
    }

    private void syncStageAlerts(SequenceRecord record,
                                 StageType stageType,
                                 AlertJobType jobType,
                                 int thresholdMinutes,
                                 String message) {
        for (StageWindow stage : record.getStagesByType(stageType)) {
            if (stage.partial() || stage.timeIn() == null) {
                continue;
            }
            LocalDateTime triggerAt = stage.timeIn();
            LocalDateTime dueAt = triggerAt.plusMinutes(thresholdMinutes);
            if (stage.timeOut() != null && !stage.timeOut().isAfter(dueAt)) {
                alertJobStorageService.cancel(record.getPlateNumber(), jobType, triggerAt);
                continue;
            }
            alertJobStorageService.upsertPending(record.getPlateNumber(), jobType, triggerAt, dueAt, message);
        }
    }
}
