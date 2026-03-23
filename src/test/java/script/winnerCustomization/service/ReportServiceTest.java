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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ReportServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void writesPlateAndSequenceClosedMarkersIntoSequencesSheetAndOmitsTypeColumn() throws Exception {
        ReportService reportService = reportService();
        RuntimeConfig runtimeConfig = runtimeConfig(reportService);
        DetectionService detectionService = detectionService(reportService);

        AppConfig config = TestConfigFactory.config();
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1002, 200, LocalDateTime.of(2026, 3, 1, 10, 10))
        ));

        byte[] report = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            var sheet = workbook.getSheet("Sequences");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Stage");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("In time");
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Out time");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("AA1111");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Drive In");
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("Sequence closed");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("Alerts");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            var events = workbook.getSheet("Events");
            assertThat(events.getRow(0).getLastCellNum()).isEqualTo((short) 6);
            assertThat(events.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Drive In");
            assertThat(events.getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void usesReportGenerationTimeForOpenStageDurationAndLeavesOutTimeBlank() throws Exception {
        ReportService reportService = reportService();
        RuntimeConfig runtimeConfig = runtimeConfig(reportService);
        DetectionService detectionService = detectionService(reportService);

        AppConfig config = TestConfigFactory.config();
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(
                new Detection(1, "AA1111", 1003, 90, LocalDateTime.of(2026, 3, 1, 10, 0))
        ));

        byte[] report = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            var row = workbook.getSheet("Sequences").getRow(2);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Service");
            assertThat(row.getCell(2).getStringCellValue()).isEmpty();
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("02:00:00");
        }
    }

    @Test
    void attachesNotificationsOnlyToMatchingPlateAndDeduplicatesSameAlertText() throws Exception {
        ReportService reportService = reportService();
        RuntimeConfig runtimeConfig = runtimeConfig(reportService);
        DetectionService detectionService = detectionService(reportService);

        AppConfig config = TestConfigFactory.config();
        AppConfig.NotificationRule firstRule = new AppConfig.NotificationRule();
        firstRule.setCameraId(1003);
        firstRule.setDelaySeconds(900);
        firstRule.setMessage("No Post in within 15 minutes");
        AppConfig.NotificationRule duplicateRule = new AppConfig.NotificationRule();
        duplicateRule.setCameraId(1004);
        duplicateRule.setDelaySeconds(900);
        duplicateRule.setMessage("No Post in within 15 minutes");
        config.setNotifications(List.of(firstRule, duplicateRule));

        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(List.of(
                new Detection(1, "AA1111", 1003, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1004, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(3, "BB2222", 1003, null, LocalDateTime.of(2026, 3, 1, 10, 1))
        ));

        byte[] report = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            List<String> rowsForPlate = new ArrayList<>();
            for (int rowIndex = 1; rowIndex <= workbook.getSheet("Events").getLastRowNum(); rowIndex++) {
                var row = workbook.getSheet("Events").getRow(rowIndex);
                if (row != null && row.getCell(0) != null && "AA1111".equals(row.getCell(0).getStringCellValue())) {
                    rowsForPlate.add(row.getCell(5) == null ? "" : row.getCell(5).getStringCellValue());
                }
            }
            assertThat(rowsForPlate).allMatch(cell -> !cell.contains("BB2222"));
            assertThat(rowsForPlate).anyMatch(cell -> cell.contains("AA1111"));
            assertThat(rowsForPlate).filteredOn(cell -> !cell.isBlank()).allMatch(cell -> !cell.contains(" | "));
        }
    }

    private ReportService reportService() {
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
        }, new TelegramNotifier(new com.fasterxml.jackson.databind.ObjectMapper()), clock);
        return new ReportService(runtimeConfig, detectionService, new SequenceEngine(), sequenceStorageService, notificationService, clock);
    }

    private RuntimeConfig runtimeConfig(ReportService reportService) throws Exception {
        var field = ReportService.class.getDeclaredField("runtimeConfig");
        field.setAccessible(true);
        return (RuntimeConfig) field.get(reportService);
    }

    private DetectionService detectionService(ReportService reportService) throws Exception {
        var field = ReportService.class.getDeclaredField("detectionService");
        field.setAccessible(true);
        return (DetectionService) field.get(reportService);
    }
}
