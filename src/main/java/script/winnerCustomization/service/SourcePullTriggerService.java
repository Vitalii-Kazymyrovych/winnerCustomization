package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.repository.DetectionRepository;
import script.winnerCustomization.repository.SequenceRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SourcePullTriggerService {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final DetectionRepository detectionRepository;
    private final SequenceRepository sequenceRepository;
    private final StageSequenceProcessor processor;
    private final RuntimeConfig runtimeConfig;
    private final Clock clock;
    private volatile LocalDateTime lastTriggerAt;

    public SourcePullTriggerService(DetectionRepository detectionRepository,
                                    SequenceRepository sequenceRepository,
                                    StageSequenceProcessor processor,
                                    RuntimeConfig runtimeConfig,
                                    Clock clock) {
        this.detectionRepository = detectionRepository;
        this.sequenceRepository = sequenceRepository;
        this.processor = processor;
        this.runtimeConfig = runtimeConfig;
        this.clock = clock;
    }

    public TriggerResult triggerPull() {
        LocalDateTime now = LocalDateTime.now(clock);
        if (lastTriggerAt != null && now.isBefore(lastTriggerAt.plusSeconds(1))) {
            return new TriggerResult(Status.COOLDOWN, 0, java.time.Duration.between(now, lastTriggerAt.plusSeconds(1)).toMillis());
        }
        if (!running.compareAndSet(false, true)) {
            return new TriggerResult(Status.ALREADY_RUNNING, 0, 0);
        }
        try {
            var detections = detectionRepository.findAll();
            var result = processor.process(detections, runtimeConfig.get(), now);
            sequenceRepository.replaceAll(result.sequences());
            lastTriggerAt = now;
            return new TriggerResult(Status.TRIGGERED, detections.size(), 0);
        } finally {
            running.set(false);
        }
    }

    public enum Status { TRIGGERED, COOLDOWN, ALREADY_RUNNING }

    public record TriggerResult(Status status, int detectionsLoaded, long retryAfterMillis) {
    }
}
