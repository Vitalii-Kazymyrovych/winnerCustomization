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
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                syncConfiguredAlerts(record, config);
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

    private void syncConfiguredAlerts(SequenceRecord record, AppConfig config) {
        if (config.getWorkflow() == null || config.getWorkflow().getStages() == null) {
            return;
        }
        Map<String, AppConfig.StageConfig> stagesByName = config.getWorkflow().getStages().stream()
                .collect(Collectors.toMap(AppConfig.StageConfig::getName, Function.identity(), (left, right) -> left));

        for (StageWindow stage : record.stagesChronologically()) {
            if (stage.partial() || stage.timeIn() == null) {
                continue;
            }
            AppConfig.StageConfig stageConfig = stagesByName.get(stage.stageName());
            if (stageConfig == null || stageConfig.getStartTriggers() == null) {
                continue;
            }
            AlertJobType jobType = alertJobType(stage.stageName());
            if (jobType == null) {
                continue;
            }
            AppConfig.TriggerConfig notifiedTrigger = stageConfig.getStartTriggers().stream()
                    .filter(trigger -> trigger.getNotification() != null && trigger.getNotification().isEnabled())
                    .findFirst()
                    .orElse(null);
            if (notifiedTrigger == null || notifiedTrigger.getNotification().getDelayMinutes() == null) {
                continue;
            }
            syncStageAlert(record, stage, notifiedTrigger, jobType);
        }
    }

    private void syncStageAlert(SequenceRecord record,
                                StageWindow stage,
                                AppConfig.TriggerConfig trigger,
                                AlertJobType jobType) {
        int thresholdMinutes = trigger.getNotification().getDelayMinutes();
        LocalDateTime dueAt = stage.timeIn().plusMinutes(thresholdMinutes);
        String message = renderMessage(trigger, thresholdMinutes, stage.reportLabel());
        if (stage.timeOut() != null && !stage.timeOut().isAfter(dueAt)) {
            alertJobStorageService.cancel(record.getPlateNumber(), jobType, stage.timeIn());
            return;
        }
        alertJobStorageService.upsertPending(record.getPlateNumber(), jobType, stage.timeIn(), dueAt, message);
    }

    private AlertJobType alertJobType(String stageName) {
        if (Objects.equals(stageName, "drive_in")) {
            return AlertJobType.DRIVE_IN_OUT_MISSING;
        }
        if (Objects.equals(stageName, "service")) {
            return AlertJobType.SERVICE_POST_IN_MISSING;
        }
        return null;
    }

    private String renderMessage(AppConfig.TriggerConfig trigger, int thresholdMinutes, String stageLabel) {
        String template = trigger.getNotification().getTemplate();
        if (template == null) {
            return "";
        }
        return template
                .replace("{{threshold}}", String.valueOf(thresholdMinutes))
                .replace("{{stage}}", stageLabel == null ? "" : stageLabel);
    }
}
