package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourcePullTriggerServiceTest {
    @Mock
    private DetectionService detectionService;

    @Test
    void shouldTriggerAndApplyCooldown() {
        MutableClock clock = new MutableClock(1_000L);
        SourcePullTriggerService service = new SourcePullTriggerService(detectionService, clock);
        when(detectionService.loadAllDetections()).thenReturn(List.of());

        SourcePullTriggerService.TriggerResult first = service.triggerPull();
        SourcePullTriggerService.TriggerResult second = service.triggerPull();

        assertThat(first.status()).isEqualTo(SourcePullTriggerService.Status.TRIGGERED);
        assertThat(second.status()).isEqualTo(SourcePullTriggerService.Status.COOLDOWN);
        assertThat(second.retryAfterMillis()).isEqualTo(30_000L);
        verify(detectionService, times(1)).loadAllDetections();
    }

    @Test
    void shouldAllowTriggerAfterCooldownPassed() {
        MutableClock clock = new MutableClock(5_000L);
        SourcePullTriggerService service = new SourcePullTriggerService(detectionService, clock);
        when(detectionService.loadAllDetections()).thenReturn(List.of());

        service.triggerPull();
        clock.plusMillis(30_000L);
        SourcePullTriggerService.TriggerResult result = service.triggerPull();

        assertThat(result.status()).isEqualTo(SourcePullTriggerService.Status.TRIGGERED);
        verify(detectionService, times(2)).loadAllDetections();
    }

    @Test
    void shouldReturnRunningWhenSecondTriggerComesInParallel() throws Exception {
        MutableClock clock = new MutableClock(7_000L);
        SourcePullTriggerService service = new SourcePullTriggerService(detectionService, clock);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(detectionService.loadAllDetections()).thenAnswer(invocation -> {
            started.countDown();
            finish.await(1, TimeUnit.SECONDS);
            return List.of();
        });

        CompletableFuture<SourcePullTriggerService.TriggerResult> firstFuture = CompletableFuture.supplyAsync(service::triggerPull);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        SourcePullTriggerService.TriggerResult second = service.triggerPull();
        finish.countDown();
        SourcePullTriggerService.TriggerResult first = firstFuture.get(1, TimeUnit.SECONDS);

        assertThat(first.status()).isEqualTo(SourcePullTriggerService.Status.TRIGGERED);
        assertThat(second.status()).isEqualTo(SourcePullTriggerService.Status.RUNNING);
        verify(detectionService, times(1)).loadAllDetections();
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(long millis) {
            this.instant = Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void plusMillis(long millis) {
            this.instant = this.instant.plusMillis(millis);
        }
    }
}
