package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AlertJobRecord;
import script.winnerCustomization.model.AlertJobType;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageType;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertSchedulerServiceTest {
    @Mock
    private RuntimeConfig runtimeConfig;
    @Mock
    private DetectionService detectionService;
    @Mock
    private SequenceEngine sequenceEngine;
    @Mock
    private AlertJobStorageService alertJobStorageService;
    @Mock
    private TelegramNotifier telegramNotifier;
    @Mock
    private Clock clock;

    @InjectMocks
    private AlertSchedulerService alertSchedulerService;

    @Test
    void shouldCreatePendingJobsDuringSync() {
        AppConfig config = new AppConfig();
        AppConfig.TimingConfig timing = new AppConfig.TimingConfig();
        timing.setDriveInToDriveOutAlertMinutes(15);
        timing.setServiceToPostAlertMinutes(20);
        config.setTiming(timing);

        Detection detection = new Detection(1L, "AA1111", 10, 90, LocalDateTime.now());
        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.of(2026, 1, 1, 10, 0));
        record.addStage(new StageWindow(StageType.DRIVE_IN,
                LocalDateTime.of(2026, 1, 1, 10, 0),
                null,
                null,
                "",
                false,
                1));
        record.addStage(new StageWindow(StageType.SERVICE,
                LocalDateTime.of(2026, 1, 1, 10, 5),
                null,
                null,
                "",
                false,
                2));
        record.setFinishedAt(LocalDateTime.of(2026, 1, 1, 10, 5));

        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));
        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(record));

        alertSchedulerService.syncPendingJobs();

        verify(alertJobStorageService).initialize();
        verify(alertJobStorageService).upsertPending(
                "AA1111",
                AlertJobType.DRIVE_IN_OUT_MISSING,
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 15),
                "No Drive in (out) within 15 minutes"
        );
        verify(alertJobStorageService).upsertPending(
                "AA1111",
                AlertJobType.SERVICE_POST_IN_MISSING,
                LocalDateTime.of(2026, 1, 1, 10, 5),
                LocalDateTime.of(2026, 1, 1, 10, 25),
                "No Post in within 20 minutes"
        );
    }

    @Test
    void shouldCancelJobsWhenStagesClosedBeforeDeadline() {
        AppConfig config = new AppConfig();
        config.setTiming(new AppConfig.TimingConfig());

        Detection detection = new Detection(1L, "AA1111", 10, 90, LocalDateTime.now());
        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.of(2026, 1, 1, 10, 0));
        record.addStage(new StageWindow(StageType.DRIVE_IN,
                LocalDateTime.of(2026, 1, 1, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 3),
                null,
                "",
                false,
                1));
        record.addStage(new StageWindow(StageType.SERVICE,
                LocalDateTime.of(2026, 1, 1, 10, 5),
                LocalDateTime.of(2026, 1, 1, 10, 7),
                null,
                "",
                false,
                2));
        record.setFinishedAt(LocalDateTime.of(2026, 1, 1, 10, 7));

        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));
        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(record));

        alertSchedulerService.syncPendingJobs();

        verify(alertJobStorageService).cancel("AA1111", AlertJobType.DRIVE_IN_OUT_MISSING, LocalDateTime.of(2026, 1, 1, 10, 0));
        verify(alertJobStorageService).cancel("AA1111", AlertJobType.SERVICE_POST_IN_MISSING, LocalDateTime.of(2026, 1, 1, 10, 5));
    }

    @Test
    void shouldDispatchDueJobs() {
        AppConfig config = new AppConfig();
        AppConfig.NotificationsConfig notifications = new AppConfig.NotificationsConfig();
        notifications.setEnabled(true);
        config.setNotifications(notifications);

        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 11, 0);
        AlertJobRecord job = new AlertJobRecord(12L, "AA1111", AlertJobType.DRIVE_IN_OUT_MISSING,
                now.minusMinutes(20), now.minusMinutes(5), "No Drive in (out) within 15 minutes");

        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T11:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(runtimeConfig.get()).thenReturn(config);
        when(alertJobStorageService.findDuePending(now, 100)).thenReturn(List.of(job));

        alertSchedulerService.dispatchDueAlerts();

        verify(alertJobStorageService).initialize();
        verify(telegramNotifier).sendIfEnabled(notifications, "Plate AA1111: No Drive in (out) within 15 minutes");
        verify(alertJobStorageService).markSent(12L, now);
    }

    @Test
    void shouldSkipDispatchWhenNoDueJobs() {
        AppConfig config = new AppConfig();
        config.setNotifications(new AppConfig.NotificationsConfig());

        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 11, 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T11:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(runtimeConfig.get()).thenReturn(config);
        when(alertJobStorageService.findDuePending(now, 100)).thenReturn(List.of());

        alertSchedulerService.dispatchDueAlerts();

        verify(telegramNotifier, never()).sendIfEnabled(any(), any());
        verify(alertJobStorageService, never()).markSent(anyLong(), any());
    }
}
