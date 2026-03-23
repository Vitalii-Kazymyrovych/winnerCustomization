package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;

@Service
public class SourceRefreshSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(SourceRefreshSchedulerService.class);

    private final SourcePullTriggerService sourcePullTriggerService;
    private final RuntimeConfig runtimeConfig;

    public SourceRefreshSchedulerService(SourcePullTriggerService sourcePullTriggerService, RuntimeConfig runtimeConfig) {
        this.sourcePullTriggerService = sourcePullTriggerService;
        this.runtimeConfig = runtimeConfig;
    }

    @Scheduled(
            initialDelayString = "#{@runtimeConfig.get().getSourceRefresh().getIntervalSeconds() * 1000L}",
            fixedDelayString = "#{@runtimeConfig.get().getSourceRefresh().getIntervalSeconds() * 1000L}"
    )
    public void refreshSequencesFromSource() {
        var refreshConfig = runtimeConfig.get().getSourceRefresh();
        if (!refreshConfig.isEnabled()) {
            return;
        }
        long intervalSeconds = refreshConfig.getIntervalSeconds();
        log.info("Starting scheduled source refresh (interval={}s)", intervalSeconds);
        try {
            SourcePullTriggerService.TriggerResult result = sourcePullTriggerService.triggerPull();
            switch (result.status()) {
                case TRIGGERED -> log.info(
                        "Scheduled source refresh finished: detectionsLoaded={}, sequencesPersisted={}, sampleIds={}",
                        result.detectionsLoaded(),
                        result.sequencesPersisted(),
                        result.sampleDetectionIds());
                case COOLDOWN -> log.info(
                        "Scheduled source refresh skipped due to cooldown; retry after {} ms",
                        result.retryAfterMillis());
                case ALREADY_RUNNING -> log.info("Scheduled source refresh skipped because another refresh is still running");
            }
        } catch (Exception exception) {
            log.error("Scheduled source refresh failed; next retry in {} seconds", intervalSeconds, exception);
        }
    }
}
