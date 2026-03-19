package script.winnerCustomization.service;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageType;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
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

    @InjectMocks
    private ReportService reportService;

    @Test
    void shouldBuildReportPersistRecordsAndCreateStageRows() throws Exception {
        AppConfig config = new AppConfig();
        config.setNotifications(new AppConfig.NotificationsConfig());
        when(runtimeConfig.get()).thenReturn(config);

        Detection detection = new Detection(1L, "AA1111", 10, 90, LocalDateTime.now());
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));

        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.of(2026, 3, 18, 10, 0));
        record.addStage(new StageWindow(StageType.DRIVE_IN,
                LocalDateTime.of(2026, 3, 18, 10, 0),
                LocalDateTime.of(2026, 3, 18, 10, 5),
                "",
                false,
                1));
        record.addStage(new StageWindow(StageType.SERVICE,
                LocalDateTime.of(2026, 3, 18, 10, 10),
                LocalDateTime.of(2026, 3, 18, 10, 24, 59),
                "No Post in within 15 minutes",
                false,
                2));
        record.addStage(new StageWindow(StageType.POST,
                LocalDateTime.of(2026, 3, 18, 10, 25),
                null,
                "",
                false,
                3));
        record.setFinishedAt(LocalDateTime.of(2026, 3, 18, 10, 25));

        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(record));

        byte[] data = reportService.buildReport();

        verify(sequenceStorageService, timeout(1000)).initialize();
        verify(sequenceStorageService, timeout(1000)).replaceAll(List.of(record));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet stageSheet = workbook.getSheet("Sequences");
            assertThat(stageSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Stage");
            assertThat(stageSheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("AA1111");
            assertThat(stageSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Drive In");
            assertThat(stageSheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Service");
            assertThat(stageSheet.getRow(3).getCell(4).getStringCellValue()).isEqualTo("No Post in within 15 minutes");

            XSSFSheet eventsSheet = workbook.getSheet("Events");
            assertThat(eventsSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Plate");
            assertThat(eventsSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Drive In");
            assertThat(eventsSheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Service");
            assertThat(eventsSheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("00:14:59");
        }
    }

    @Test
    void shouldRenderPartialAndBackyardStagesOnFlatSheet() throws Exception {
        AppConfig config = new AppConfig();
        config.setNotifications(new AppConfig.NotificationsConfig());
        when(runtimeConfig.get()).thenReturn(config);

        Detection detection = new Detection(1L, "BB2222", 10, 90, LocalDateTime.now());
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));

        SequenceRecord record = new SequenceRecord("BB2222", LocalDateTime.of(2026, 3, 16, 12, 0));
        record.addStage(new StageWindow(StageType.PARKING,
                null,
                LocalDateTime.of(2026, 3, 16, 12, 5),
                "",
                true,
                1));
        record.addStage(new StageWindow(StageType.BACKYARD,
                LocalDateTime.of(2026, 3, 16, 12, 5),
                LocalDateTime.of(2026, 3, 16, 12, 12),
                "",
                false,
                2));
        record.setFinishedAt(LocalDateTime.of(2026, 3, 16, 12, 12));
        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(record));

        byte[] data = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet sheet = workbook.getSheet("Events");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Parking");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("2026-03-16T12:05");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Backyard");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("00:07:00");
        }
    }

    @Test
    void shouldPersistReportIntoConfiguredFolder(@TempDir Path tempDir) throws Exception {
        AppConfig config = new AppConfig();
        AppConfig.ReportsConfig reports = new AppConfig.ReportsConfig();
        reports.setOutputDirectory(tempDir.resolve("xlsx").toString());
        config.setReports(reports);
        when(runtimeConfig.get()).thenReturn(config);

        Detection detection = new Detection(1L, "CC0001", 10, 90, LocalDateTime.now());
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));

        SequenceRecord record = new SequenceRecord("CC0001", LocalDateTime.now());
        record.setFinishedAt(LocalDateTime.now());
        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(record));

        byte[] body = reportService.buildReport();

        Path reportPath = tempDir.resolve("xlsx").resolve("sequences.xlsx");
        assertThat(Files.exists(reportPath)).isTrue();
        assertThat(Files.size(reportPath)).isEqualTo(body.length);
    }
}
