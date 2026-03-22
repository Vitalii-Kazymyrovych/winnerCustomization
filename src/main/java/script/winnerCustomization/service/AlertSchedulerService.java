package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;

import java.util.List;

@Service
public class AlertSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(AlertSchedulerService.class);
    private static final int SEND_BATCH_SIZE = 100;

    private final RuntimeConfig runtimeConfig;
    private final DetectionService detectionService;
    private final NotificationService notificationService;

    public AlertSchedulerService(RuntimeConfig runtimeConfig,
                                 DetectionService detectionService,
                                 NotificationService notificationService) {
        this.runtimeConfig = runtimeConfig;
        this.detectionService = detectionService;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelayString = "${alerts.sync.delay.millis:10000}")
    public void syncPendingJobs() {
        try {
            AppConfig config = runtimeConfig.get();
            List<Detection> detections = detectionService.loadAllDetections();
            notificationService.syncPendingNotifications(detections, config);
        } catch (Exception exception) {
            log.warn("Alert sync failed: {}", exception.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${alerts.dispatch.delay.millis:5000}")
    public void dispatchDueAlerts() {
        try {
            notificationService.dispatchDueNotifications(runtimeConfig.get(), SEND_BATCH_SIZE);
        } catch (Exception exception) {
            log.warn("Alert dispatch failed: {}", exception.getMessage());
        }
    }
}
