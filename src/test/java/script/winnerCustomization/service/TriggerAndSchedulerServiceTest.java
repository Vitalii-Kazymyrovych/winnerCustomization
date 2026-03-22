package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TriggerAndSchedulerServiceTest {
    @Test
    void sourcePullTriggerHandlesTriggeredCooldownAndRunningStates() {
        DetectionService detectionService = Mockito.mock(DetectionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-03-22T12:00:00Z"), ZoneOffset.UTC);
        when(detectionService.loadAllDetections()).thenReturn(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 22, 12, 0))
        ));
        SourcePullTriggerService service = new SourcePullTriggerService(detectionService, clock);

        SourcePullTriggerService.TriggerResult first = service.triggerPull();
        SourcePullTriggerService.TriggerResult second = service.triggerPull();

        assertThat(first.status()).isEqualTo(SourcePullTriggerService.Status.TRIGGERED);
        assertThat(first.detectionsLoaded()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(SourcePullTriggerService.Status.COOLDOWN);
        assertThat(second.retryAfterMillis()).isPositive();
    }

    @Test
    void sourcePullTriggerReturnsRunningWhenNestedCallHoldsLock() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-22T12:00:00Z"), ZoneOffset.UTC);
        class ReentrantDetectionService extends DetectionService {
            private SourcePullTriggerService service;
            private SourcePullTriggerService.TriggerResult nestedResult;

            private ReentrantDetectionService() {
                super(Mockito.mock(script.winnerCustomization.repository.DetectionRepository.class));
            }

            @Override
            public List<Detection> loadAllDetections() {
                nestedResult = service.triggerPull();
                return List.of();
            }
        }

        ReentrantDetectionService detectionService = new ReentrantDetectionService();
        SourcePullTriggerService service = new SourcePullTriggerService(detectionService, clock);
        detectionService.service = service;

        SourcePullTriggerService.TriggerResult result = service.triggerPull();

        assertThat(result.status()).isEqualTo(SourcePullTriggerService.Status.TRIGGERED);
        assertThat(detectionService.nestedResult.status()).isEqualTo(SourcePullTriggerService.Status.RUNNING);
    }

    @Test
    void alertSchedulerInvokesSyncAndDispatchAndSwallowsFailures() {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        DetectionService detectionService = Mockito.mock(DetectionService.class);
        NotificationService notificationService = Mockito.mock(NotificationService.class);
        var config = script.winnerCustomization.config.TestFixtures.configWithReportDirectory("");
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of());

        AlertSchedulerService service = new AlertSchedulerService(runtimeConfig, detectionService, notificationService);
        service.syncPendingJobs();
        service.dispatchDueAlerts();

        verify(notificationService).syncPendingNotifications(List.of(), config);
        verify(notificationService).dispatchDueNotifications(config, 100);

        doThrow(new RuntimeException("boom")).when(detectionService).loadAllDetections();
        doThrow(new RuntimeException("boom")).when(notificationService).dispatchDueNotifications(config, 100);

        service.syncPendingJobs();
        service.dispatchDueAlerts();

        verify(notificationService).syncPendingNotifications(List.of(), config);
    }
}
