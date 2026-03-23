package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
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
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void triggersNotificationWhenPlateStaysOnConfiguredCamera() {
        NotificationService service = service(new InMemoryNotificationRepository());
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
        NotificationService service = service(new InMemoryNotificationRepository());
        List<SequenceRecord.NotificationEvent> events = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1003, null, LocalDateTime.of(2026, 3, 1, 10, 10))
        ), TestConfigFactory.config());

        assertThat(events).isEmpty();
    }


    @Test
    void repeatedDetectionsOnSameCameraDoNotResetPendingAlarm() {
        NotificationService service = service(new InMemoryNotificationRepository());

        List<SequenceRecord.NotificationEvent> events = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 5)),
                new Detection(3, "AA1111", 1003, null, LocalDateTime.of(2026, 3, 1, 10, 20))
        ), TestConfigFactory.config());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().triggeredAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 15));
    }

    @Test
    void deduplicatesEqualMessagesTriggeredAtSameTimeFromDifferentCameras() {
        NotificationService service = service(new InMemoryNotificationRepository());
        AppConfig config = TestConfigFactory.config();
        AppConfig.NotificationRule duplicateRule = new AppConfig.NotificationRule();
        duplicateRule.setCameraId(1002);
        duplicateRule.setDelaySeconds(900);
        duplicateRule.setMessage("Автомобіль довго стоїть на Drive-In");
        config.setNotifications(List.of(config.getNotifications().getFirst(), duplicateRule));

        List<SequenceRecord.NotificationEvent> events = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1002, null, LocalDateTime.of(2026, 3, 1, 10, 0))
        ), config);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().message()).isEqualTo("Автомобіль довго стоїть на Drive-In: AA1111");
    }

    @Test
    void evaluateHonorsDirectionRangesAndNullConfigProducesNoEvents() {
        NotificationService service = service(new InMemoryNotificationRepository());
        AppConfig config = TestConfigFactory.config();
        AppConfig.DirectionRange range = new AppConfig.DirectionRange();
        range.setFrom(270);
        range.setTo(90);
        config.getNotifications().getFirst().setDirectionRange(range);

        List<SequenceRecord.NotificationEvent> matched = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, 350, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1001, 350, LocalDateTime.of(2026, 3, 1, 10, 30))
        ), config);

        List<SequenceRecord.NotificationEvent> unmatched = service.evaluate(List.of(
                new Detection(1, "AA1111", 1001, 100, LocalDateTime.of(2026, 3, 1, 10, 0))
        ), config);

        assertThat(matched).hasSize(2);
        assertThat(unmatched).isEmpty();
        assertThat(service.evaluate(List.of(), null)).isEmpty();
    }

    @Test
    void syncPendingNotificationsPersistsAndCancelsPendingItems() {
        InMemoryNotificationRepository repository = new InMemoryNotificationRepository();
        NotificationService service = service(repository);

        service.syncPendingNotifications(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1003, null, LocalDateTime.of(2026, 3, 1, 10, 10)),
                new Detection(3, "BB2222", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0))
        ), TestConfigFactory.config());

        assertThat(repository.initialized).isTrue();
        assertThat(repository.cancelledKeys).contains("AA1111@1001");
        assertThat(repository.items).extracting(NotificationService.PendingNotification::plateNumber)
                .contains("AA1111", "BB2222");
    }

    @Test
    void dispatchDueNotificationsMarksItemsSent() {
        InMemoryNotificationRepository repository = new InMemoryNotificationRepository();
        repository.dueItems = List.of(new NotificationService.PendingNotification(
                11L, "AA1111", 1001,
                LocalDateTime.of(2026, 3, 1, 10, 0),
                LocalDateTime.of(2026, 3, 1, 10, 15),
                "hello"
        ));
        NotificationService service = service(repository);

        service.dispatchDueNotifications(TestConfigFactory.config(), 5);

        assertThat(repository.initialized).isTrue();
        assertThat(repository.markedSentIds).containsExactly(11L);
    }

    private NotificationService service(InMemoryNotificationRepository repository) {
        return new NotificationService(repository, new TelegramNotifier(new com.fasterxml.jackson.databind.ObjectMapper()), clock);
    }

    private static final class InMemoryNotificationRepository implements NotificationRepository {
        private final List<NotificationService.PendingNotification> items = new ArrayList<>();
        private final List<String> cancelledKeys = new ArrayList<>();
        private final List<Long> markedSentIds = new ArrayList<>();
        private List<NotificationService.PendingNotification> dueItems = List.of();
        private boolean initialized;

        @Override public void initialize() { initialized = true; }
        @Override public void upsertPending(NotificationService.PendingNotification pendingNotification) { items.add(pendingNotification); }
        @Override public void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt) { cancelledKeys.add(plateNumber + "@" + cameraId); }
        @Override public List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit) { return dueItems; }
        @Override public List<NotificationService.PendingNotification> findAll() { return items; }
        @Override public void markSent(long id, LocalDateTime sentAt) { markedSentIds.add(id); }
    }
}
