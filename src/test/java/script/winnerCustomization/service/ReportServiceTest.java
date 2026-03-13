package script.winnerCustomization.service;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {
    @Mock
    private RuntimeConfig runtimeConfig;
    @Mock
    private DetectionService detectionService;
    @Mock
    private SequenceEngine sequenceEngine;
    @Mock
    private SequenceStorageService sequenceStorageService;
    @Mock
    private TelegramNotifier telegramNotifier;

    @InjectMocks
    private ReportService reportService;

    @Test
    void shouldBuildReportPersistRecordsAndSendAlerts() throws Exception {
        AppConfig config = new AppConfig();
        AppConfig.NotificationsConfig notifications = new AppConfig.NotificationsConfig();
        notifications.setEnabled(true);
        config.setNotifications(notifications);
        when(runtimeConfig.get()).thenReturn(config);

        Detection detection = new Detection(1L, "AA1111", 10, 90, LocalDateTime.now());
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));

        SequenceRecord first = new SequenceRecord("AA1111", LocalDateTime.of(2026, 1, 1, 10, 0));
        first.setDriveInOutAt(LocalDateTime.of(2026, 1, 1, 10, 5));
        first.setFinishedAt(LocalDateTime.of(2026, 1, 1, 10, 30));
        first.addAlert("Exceeded 15 min");

        SequenceRecord second = new SequenceRecord("BB2222", LocalDateTime.of(2026, 1, 1, 11, 0));
        second.setServiceInAt(LocalDateTime.of(2026, 1, 1, 11, 2));
        second.addAlert("Exceeded 15 min");

        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(first, second));

        byte[] data = reportService.buildReport();

        verify(sequenceStorageService).initialize();
        verify(sequenceStorageService).replaceAll(List.of(first, second));
        verify(telegramNotifier).sendIfEnabled(notifications, "Plate AA1111: Exceeded 15 min");
        verify(telegramNotifier).sendIfEnabled(notifications, "Plate BB2222: Exceeded 15 min");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet sheet = workbook.getSheet("Sequences");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Plate");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("AA1111");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("BB2222");
        }
    }

    @Test
    void shouldSkipNotificationsWhenThereAreNoAlerts() throws Exception {
        AppConfig config = new AppConfig();
        config.setNotifications(new AppConfig.NotificationsConfig());
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of());
        when(sequenceEngine.build(List.of(), config)).thenReturn(List.of());

        reportService.buildReport();

        verify(telegramNotifier, never()).sendIfEnabled(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
