package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.repository.NotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest {
    @Test
    void triggersNotificationWhenPlateStaysOnConfiguredCamera() {
        NotificationService service = new NotificationService(new InMemoryNotificationRepository(), new TelegramNotifier(new com.fasterxml.jackson.databind.ObjectMapper()),
                Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC));
        List<SequenceRecord.NotificationEvent> events = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 5))
        ), TestConfigFactory.config());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().plateNumber()).isEqualTo("AA1111");
        assertThat(events.getFirst().message()).contains("AA1111");
    }

    @Test
    void cancelsPendingNotificationWhenOtherCameraAppears() {
        NotificationService service = new NotificationService(new InMemoryNotificationRepository(), new TelegramNotifier(new com.fasterxml.jackson.databind.ObjectMapper()),
                Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC));
        List<SequenceRecord.NotificationEvent> events = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1003, null, LocalDateTime.of(2026, 3, 1, 10, 10))
        ), TestConfigFactory.config());

        assertThat(events).isEmpty();
    }

    private static final class InMemoryNotificationRepository implements NotificationRepository {
        private final List<NotificationService.PendingNotification> items = new ArrayList<>();
        @Override public void initialize() {}
        @Override public void upsertPending(NotificationService.PendingNotification pendingNotification) { items.add(pendingNotification); }
        @Override public void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt) {}
        @Override public List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit) { return List.of(); }
        @Override public List<NotificationService.PendingNotification> findAll() { return items; }
        @Override public void markSent(long id, LocalDateTime sentAt) {}
    }
}
