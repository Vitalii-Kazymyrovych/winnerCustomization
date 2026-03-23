package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.repository.DetectionRepository;
import script.winnerCustomization.repository.SequenceRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
            return new TriggerResult(Status.COOLDOWN, 0, 0, java.time.Duration.between(now, lastTriggerAt.plusSeconds(1)).toMillis(), List.of());
        }
        if (!running.compareAndSet(false, true)) {
            return new TriggerResult(Status.ALREADY_RUNNING, 0, 0, 0, List.of());
        }
        try {
            var detections = detectionRepository.findAll();
            var result = processor.process(detections, runtimeConfig.get(), now);
            sequenceRepository.replaceAll(result.sequences());
            lastTriggerAt = now;
            return new TriggerResult(Status.TRIGGERED, detections.size(), result.sequences().size(), 0,
                    detections.stream().map(Detection::id).limit(10).toList());
        } finally {
            running.set(false);
        }
    }

    public enum Status { TRIGGERED, COOLDOWN, ALREADY_RUNNING }

    public record TriggerResult(Status status, int detectionsLoaded, int sequencesPersisted, long retryAfterMillis, List<Long> sampleDetectionIds) {
    }
}
