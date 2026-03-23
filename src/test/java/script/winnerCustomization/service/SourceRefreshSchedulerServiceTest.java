package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.config.TestConfigFactory;
import script.winnerCustomization.model.AppConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SourceRefreshSchedulerServiceTest {

    @Test
    void schedulerInvokesPullWhenEnabled() {
        var counter = new AtomicInteger();
        SourceRefreshSchedulerService service = new SourceRefreshSchedulerService(
                new StubSourcePullTriggerService(counter, SourcePullTriggerService.Status.TRIGGERED),
                runtimeConfig(TestConfigFactory.standardConfig()));

        service.refreshSequencesFromSource();

        assertThat(counter).hasValue(1);
    }

    @Test
    void schedulerSkipsPullWhenDisabled() {
        AppConfig config = TestConfigFactory.standardConfig();
        config.getSourceRefresh().setEnabled(false);
        var counter = new AtomicInteger();
        SourceRefreshSchedulerService service = new SourceRefreshSchedulerService(
                new StubSourcePullTriggerService(counter, SourcePullTriggerService.Status.TRIGGERED),
                runtimeConfig(config));

        service.refreshSequencesFromSource();

        assertThat(counter).hasValue(0);
    }

    private RuntimeConfig runtimeConfig(AppConfig config) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(runtimeConfig, "appConfig", new java.util.concurrent.atomic.AtomicReference<>(config));
        return runtimeConfig;
    }

    private static final class StubSourcePullTriggerService extends SourcePullTriggerService {
        private final AtomicInteger counter;
        private final Status status;

        private StubSourcePullTriggerService(AtomicInteger counter, Status status) {
            super(null, null, null, null, null);
            this.counter = counter;
            this.status = status;
        }

        @Override
        public TriggerResult triggerPull() {
            counter.incrementAndGet();
            return new TriggerResult(status, 3, 2, 0, List.of(1L, 2L, 3L));
        }
    }
}
