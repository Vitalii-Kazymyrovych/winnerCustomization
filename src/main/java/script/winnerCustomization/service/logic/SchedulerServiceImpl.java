package script.winnerCustomization.service.logic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.alerts.AlertService;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.model.AlertRecord;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.repository.SourceRepository;
import script.winnerCustomization.repository.TargetRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class SchedulerServiceImpl implements SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerServiceImpl.class);

    private final ConfigLoader configLoader;
    private final SourceRepository sourceRepository;
    private final TargetRepository targetRepository;
    private final SequenceEngineServiceImpl sequenceEngine;
    private final AlertService alertService;

    private ScheduledExecutorService scheduler;
    private LocalDateTime lastProcessedTimestamp;

    public SchedulerServiceImpl(ConfigLoader configLoader,
                                SourceRepository sourceRepository,
                                TargetRepository targetRepository,
                                SequenceEngineServiceImpl sequenceEngine,
                                AlertService alertService) {
        this.configLoader = configLoader;
        this.sourceRepository = sourceRepository;
        this.targetRepository = targetRepository;
        this.sequenceEngine = sequenceEngine;
        this.alertService = alertService;
    }

    @Override
    public void startPolling() {
        int intervalSeconds = configLoader.getConfig().getSourceRefreshSeconds();
        log.info("Starting polling scheduler with interval: {} seconds", intervalSeconds);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alpr-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(this::pollAndProcess, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stopPolling() {
        if (scheduler != null) {
            scheduler.shutdown();
            log.info("Polling scheduler stopped");
        }
    }

    private void pollAndProcess() {
        try {
            int intervalSeconds = configLoader.getConfig().getSourceRefreshSeconds();

            // 1. Fetch new detections
            List<Detection> newDetections;
            if (lastProcessedTimestamp != null) {
                newDetections = sourceRepository.fetchDetectionsAfter(lastProcessedTimestamp);
            } else {
                newDetections = sourceRepository.fetchAllDetections();
            }

            // 2. Process new detections
            if (!newDetections.isEmpty()) {
                log.info("Processing {} new detections", newDetections.size());
                sequenceEngine.processDetections(newDetections);
                lastProcessedTimestamp = newDetections.get(newDetections.size() - 1).getCreatedAt();
            }

            // 3. Perform maintenance (timeouts, durations, etc.)
            sequenceEngine.performMaintenance(intervalSeconds);

            // 4. Send any pending alerts
            List<AlertRecord> pendingAlerts = sequenceEngine.getPendingAlertSends();
            for (AlertRecord alert : pendingAlerts) {
                alertService.sendAlert(alert);
            }

            // 5. Update target DB — only active sequences, active stages, and active alerts.
            // Closed sequences and inactive stages/alerts are not touched; they persist from
            // the startup rewriteAll and do not change during polling.
            targetRepository.updateActive(sequenceEngine.getActiveSequences());

            log.debug("Poll cycle complete. Active sequences: {}", sequenceEngine.getActiveSequences().size());

        } catch (Exception e) {
            log.error("Error during poll cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * Perform the initial full load from source.
     */
    public void performInitialLoad() {
        log.info("=== Performing initial full load ===");

        // Fetch all detections
        List<Detection> allDetections = sourceRepository.fetchAllDetections();

        if (allDetections.isEmpty()) {
            log.warn("No detections found in source database");
        } else {
            // Process all detections
            sequenceEngine.processDetections(allDetections);

            // Insert historical transitionals
            sequenceEngine.insertHistoricalTransitionals();

            // Save last processed timestamp
            lastProcessedTimestamp = allDetections.get(allDetections.size() - 1).getCreatedAt();

            // Perform initial maintenance to set durations
            sequenceEngine.performMaintenance(0);

            // Write everything to target DB (full rewrite — startup step 5)
            targetRepository.rewriteAll(sequenceEngine.getAllSequences());

            log.info("Initial load complete: {} detections processed, {} sequences built",
                    allDetections.size(), sequenceEngine.getAllSequences().size());
        }
    }
}
