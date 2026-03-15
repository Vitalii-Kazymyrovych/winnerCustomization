package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SourcePullTriggerService {
    private static final Logger log = LoggerFactory.getLogger(SourcePullTriggerService.class);
    private static final long COOLDOWN_MILLIS = 30_000L;

    private final DetectionService detectionService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong nextAllowedAtMillis = new AtomicLong(0L);

    public SourcePullTriggerService(DetectionService detectionService, Clock clock) {
        this.detectionService = detectionService;
        this.clock = clock;
    }

    public TriggerResult triggerPull() {
        log.info("Manual source pull trigger requested");
        long now = clock.millis();
        long nextAllowed = nextAllowedAtMillis.get();
        if (now < nextAllowed) {
            log.info("Trigger rejected by cooldown, retryAfterMillis={}", nextAllowed - now);
            return TriggerResult.cooldown(nextAllowed - now);
        }

        if (!running.compareAndSet(false, true)) {
            log.info("Trigger rejected because another pull is already running");
            return TriggerResult.running();
        }

        try {
            now = clock.millis();
            nextAllowed = nextAllowedAtMillis.get();
            if (now < nextAllowed) {
                log.info("Trigger rejected by cooldown after lock acquisition, retryAfterMillis={}", nextAllowed - now);
                return TriggerResult.cooldown(nextAllowed - now);
            }
            List<?> detections = detectionService.loadAllDetections();
            nextAllowedAtMillis.set(clock.millis() + COOLDOWN_MILLIS);
            log.info("Trigger completed successfully, loadedDetections={}, cooldownMillis={}", detections.size(), COOLDOWN_MILLIS);
            return TriggerResult.triggered(detections.size());
        } finally {
            running.set(false);
            log.debug("Manual source pull execution lock released");
        }
    }

    public record TriggerResult(Status status, int detectionsLoaded, long retryAfterMillis) {
        public static TriggerResult triggered(int detectionsLoaded) {
            return new TriggerResult(Status.TRIGGERED, detectionsLoaded, 0L);
        }

        public static TriggerResult cooldown(long retryAfterMillis) {
            return new TriggerResult(Status.COOLDOWN, 0, retryAfterMillis);
        }

        public static TriggerResult running() {
            return new TriggerResult(Status.RUNNING, 0, 0L);
        }
    }

    public enum Status {
        TRIGGERED,
        COOLDOWN,
        RUNNING
    }
}
