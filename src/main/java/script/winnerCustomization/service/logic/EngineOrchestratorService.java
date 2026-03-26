package script.winnerCustomization.service.logic;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.AppConfig;
import script.winnerCustomization.repository.SequenceStateRepository;
import script.winnerCustomization.repository.SourceDetectionRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class EngineOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(EngineOrchestratorService.class);

    private final SourceDetectionRepository sourceDetectionRepository;
    private final SequenceEngineService sequenceEngineService;
    private final SequenceStateRepository sequenceStateRepository;
    private final AppConfig appConfig;

    private LocalDateTime lastProcessedTimestamp;
    private LocalDateTime lastPollTimestamp;

    public EngineOrchestratorService(SourceDetectionRepository sourceDetectionRepository,
                                     SequenceEngineService sequenceEngineService,
                                     SequenceStateRepository sequenceStateRepository,
                                     AppConfig appConfig) {
        this.sourceDetectionRepository = sourceDetectionRepository;
        this.sequenceEngineService = sequenceEngineService;
        this.sequenceStateRepository = sequenceStateRepository;
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void startupRebuild() {
        rebuildAll();
        lastPollTimestamp = LocalDateTime.now(ZoneOffset.UTC);
    }

    @Scheduled(fixedDelayString = "#{@appConfig.sourceRefreshSeconds * 1000}")
    public void pollAndRebuild() {
        applyIncremental();
    }

    private synchronized void rebuildAll() {
        log.info("Rebuilding state from source detections");
        var detections = sourceDetectionRepository.findAll();
        var snapshot = sequenceEngineService.rebuild(detections, LocalDateTime.now(ZoneOffset.UTC));
        sequenceStateRepository.replaceAll(snapshot.sequences(), snapshot.alerts());
        lastProcessedTimestamp = snapshot.lastProcessedTimestamp();
        log.info("Rebuild finished. sequences={}, alerts={}, lastProcessed={}",
            snapshot.sequences().size(), snapshot.alerts().size(), lastProcessedTimestamp);
    }

    private synchronized void applyIncremental() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (lastProcessedTimestamp == null) {
            rebuildAll();
            lastPollTimestamp = nowUtc;
            return;
        }
        var newDetections = sourceDetectionRepository.findNewerThan(lastProcessedTimestamp);
        var snapshot = sequenceEngineService.applyIncremental(
            sequenceStateRepository.findAllSequences(),
            sequenceStateRepository.findAllAlerts(),
            newDetections,
            lastPollTimestamp,
            nowUtc
        );
        sequenceStateRepository.replaceAll(snapshot.sequences(), snapshot.alerts());
        if (snapshot.lastProcessedTimestamp() != null) {
            lastProcessedTimestamp = snapshot.lastProcessedTimestamp();
        }
        lastPollTimestamp = nowUtc;
        log.info("Incremental poll finished. newDetections={}, sequences={}, alerts={}, lastProcessed={}",
            newDetections.size(), snapshot.sequences().size(), snapshot.alerts().size(), lastProcessedTimestamp);
    }
}
