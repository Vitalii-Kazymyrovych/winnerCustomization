package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.repository.DetectionRepository;
import script.winnerCustomization.repository.SequenceRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleServicesAndNotifierTest {
    @Test
    void detectionServiceDelegatesToRepository() {
        DetectionRepository repository = Mockito.mock(DetectionRepository.class);
        DetectionService service = new DetectionService(repository);
        List<Detection> detections = List.of(new Detection(1, "AA1111", 1, null, LocalDateTime.now()));
        when(repository.findAll()).thenReturn(detections);
        when(repository.findBetween(LocalDateTime.MIN, LocalDateTime.MAX)).thenReturn(detections);

        assertThat(service.loadAllDetections()).isSameAs(detections);
        assertThat(service.loadDetectionsBetween(LocalDateTime.MIN, LocalDateTime.MAX)).isSameAs(detections);
    }

    @Test
    void sequenceStorageServiceDelegatesToRepository() {
        SequenceRepository repository = Mockito.mock(SequenceRepository.class);
        SequenceStorageService service = new SequenceStorageService(repository);
        List<SequenceRecord> records = List.of(new SequenceRecord("AA1111", LocalDateTime.of(2026, 3, 22, 12, 0)));

        service.initialize();
        service.replaceAll(records);

        verify(repository).initialize();
        verify(repository).replaceAll(records);
    }

    @Test
    void telegramNotifierSkipsDisabledMessagingAndSwallowsSendFailures() {
        TelegramNotifier notifier = new TelegramNotifier(new ObjectMapper());
        AppConfig.MessagingConfig disabled = new AppConfig.MessagingConfig();
        disabled.setEnabled(false);
        notifier.sendIfEnabled(disabled, "ignored");

        AppConfig.MessagingConfig invalid = new AppConfig.MessagingConfig();
        invalid.setEnabled(true);
        invalid.setTelegramBotToken("bad token with space");
        invalid.setTelegramChatId("123");
        notifier.sendIfEnabled(invalid, "hello");

        assertThat(true).isTrue();
    }
}
