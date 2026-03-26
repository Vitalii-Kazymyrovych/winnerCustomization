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
    }

    @Scheduled(fixedDelayString = "#{@appConfig.sourceRefreshSeconds * 1000}")
    public void pollAndRebuild() {
        rebuildAll();
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
}
