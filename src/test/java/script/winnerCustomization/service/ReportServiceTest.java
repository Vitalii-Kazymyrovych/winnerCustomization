package script.winnerCustomization.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.repository.NotificationRepository;
import script.winnerCustomization.repository.SequenceRepository;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ReportServiceTest {
    @Test
    void writesStageTypeAndAlertsIntoWorkbook() throws Exception {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        DetectionService detectionService = Mockito.mock(DetectionService.class);
        SequenceStorageService sequenceStorageService = new SequenceStorageService(new SequenceRepository() {
            @Override public void initialize() {}
            @Override public List<script.winnerCustomization.model.SequenceRecord> findAll() { return List.of(); }
            @Override public void replaceAll(List<script.winnerCustomization.model.SequenceRecord> entities) {}
        });
        NotificationService notificationService = new NotificationService(new NotificationRepository() {
            @Override public void initialize() {}
            @Override public void upsertPending(NotificationService.PendingNotification pendingNotification) {}
            @Override public void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt) {}
            @Override public List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit) { return List.of(); }
            @Override public List<NotificationService.PendingNotification> findAll() { return List.of(); }
            @Override public void markSent(long id, LocalDateTime sentAt) {}
        }, new TelegramNotifier(new com.fasterxml.jackson.databind.ObjectMapper()), Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC));
        ReportService reportService = new ReportService(runtimeConfig, detectionService, new SequenceEngine(), sequenceStorageService, notificationService);

        AppConfig config = TestConfigFactory.config();
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 20))
        ));

        byte[] report = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            assertThat(workbook.getSheet("Sequences").getRow(1).getCell(0).getStringCellValue()).isEqualTo("AA1111");
            assertThat(workbook.getSheet("Events").getRow(1).getCell(2).getStringCellValue()).isEqualTo("REAL");
            assertThat(workbook.getSheet("Events").getRow(1).getCell(6).getStringCellValue()).contains("AA1111");
        }
    }

    @Test
    void attachesNotificationsOnlyToMatchingPlate() throws Exception {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        DetectionService detectionService = Mockito.mock(DetectionService.class);
        SequenceStorageService sequenceStorageService = new SequenceStorageService(new SequenceRepository() {
            @Override public void initialize() {}
            @Override public List<script.winnerCustomization.model.SequenceRecord> findAll() { return List.of(); }
            @Override public void replaceAll(List<script.winnerCustomization.model.SequenceRecord> entities) {}
        });
        NotificationService notificationService = new NotificationService(new NotificationRepository() {
            @Override public void initialize() {}
            @Override public void upsertPending(NotificationService.PendingNotification pendingNotification) {}
            @Override public void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt) {}
            @Override public List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit) { return List.of(); }
            @Override public List<NotificationService.PendingNotification> findAll() { return List.of(); }
            @Override public void markSent(long id, LocalDateTime sentAt) {}
        }, new TelegramNotifier(new com.fasterxml.jackson.databind.ObjectMapper()), Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC));
        ReportService reportService = new ReportService(runtimeConfig, detectionService, new SequenceEngine(), sequenceStorageService, notificationService);

        AppConfig config = TestConfigFactory.config();
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(
                new Detection(1, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "BB2222", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 1)),
                new Detection(3, "AA1111", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 20)),
                new Detection(4, "BB2222", 1001, null, LocalDateTime.of(2026, 3, 1, 10, 21))
        ));

        byte[] report = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            List<String> rowsForPlate = new java.util.ArrayList<>();
            for (int rowIndex = 1; rowIndex <= workbook.getSheet("Events").getLastRowNum(); rowIndex++) {
                var row = workbook.getSheet("Events").getRow(rowIndex);
                if (row != null && row.getCell(0) != null && "AA1111".equals(row.getCell(0).getStringCellValue())) {
                    rowsForPlate.add(row.getCell(6) == null ? "" : row.getCell(6).getStringCellValue());
                }
            }
            assertThat(rowsForPlate).allMatch(cell -> !cell.contains("BB2222"));
            assertThat(rowsForPlate).anyMatch(cell -> cell.contains("AA1111"));
        }
    }
}
