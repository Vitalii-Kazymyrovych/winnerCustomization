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
    void shouldBuildReportPersistRecordsAndCreateSecondSheet() throws Exception {
        AppConfig config = new AppConfig();
        config.setNotifications(new AppConfig.NotificationsConfig());
        when(runtimeConfig.get()).thenReturn(config);

        Detection detection = new Detection(1L, "AA1111", 10, 90, LocalDateTime.now());
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));

        SequenceRecord first = new SequenceRecord("AA1111", LocalDateTime.of(2026, 1, 1, 10, 0));
        first.setDriveInOutAt(LocalDateTime.of(2026, 1, 1, 10, 5));
        first.setServiceInAt(LocalDateTime.of(2026, 1, 1, 10, 10));
        first.setPostInAt(LocalDateTime.of(2026, 1, 1, 10, 20));
        first.setPostOutAt(LocalDateTime.of(2026, 1, 1, 10, 25));
        first.setSecondServiceInAt(LocalDateTime.of(2026, 1, 1, 10, 25));
        first.setServiceOutAt(LocalDateTime.of(2026, 1, 1, 10, 40));
        first.setParkingInAt(LocalDateTime.of(2026, 1, 1, 10, 50));
        first.setParkingOutAt(LocalDateTime.of(2026, 1, 1, 10, 55));
        first.setFinishedAt(LocalDateTime.of(2026, 1, 1, 10, 55));
        first.addAlert("Exceeded 15 min");

        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(first));

        byte[] data = reportService.buildReport();

        verify(sequenceStorageService, timeout(1000)).initialize();
        verify(sequenceStorageService, timeout(1000)).replaceAll(List.of(first));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet stageSheet = workbook.getSheet("Sequences");
            assertThat(stageSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Stage");
            assertThat(stageSheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("AA1111");
            assertThat(stageSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Drive in");

            XSSFSheet eventsSheet = workbook.getSheet("Events");
            assertThat(eventsSheet).isNotNull();
            assertThat(eventsSheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Номер");
            assertThat(eventsSheet.getRow(0).getCell(5).getStringCellValue())
                    .isEqualTo("Для события Out время проведенное на этапе");
            assertThat(eventsSheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("Out");
            assertThat(eventsSheet.getRow(2).getCell(5).getStringCellValue()).isEqualTo("00:05:00");
        }
    }


    @Test
    void shouldCloseServiceStageAtPostInWhenServiceOutIsMissing() throws Exception {
        AppConfig config = new AppConfig();
        config.setNotifications(new AppConfig.NotificationsConfig());
        when(runtimeConfig.get()).thenReturn(config);

        Detection detection = new Detection(1L, "AA0029TT", 10, 90, LocalDateTime.now());
        when(detectionService.loadAllDetections()).thenReturn(List.of(detection));

        SequenceRecord record = new SequenceRecord("AA0029TT", LocalDateTime.of(2026, 3, 16, 12, 31, 51, 453_000_000));
        record.setDriveInOutAt(LocalDateTime.of(2026, 3, 16, 12, 36, 38, 186_000_000));
        record.setServiceInAt(LocalDateTime.of(2026, 3, 16, 12, 40, 48, 531_000_000));
        record.setPostInAt(LocalDateTime.of(2026, 3, 16, 13, 21, 19, 644_000_000));
        record.setServiceFirstFinishedAt(LocalDateTime.of(2026, 3, 16, 13, 21, 19, 644_000_000));

        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(record));

        byte[] data = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet sheet = workbook.getSheet("Sequences");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Service");
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("2026-03-16T13:21:19.644");
            assertThat(sheet.getRow(3).getCell(3).getStringCellValue()).isEqualTo("00:40:31");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Post");
            assertThat(sheet.getRow(4).getCell(2).getStringCellValue()).isEqualTo("");
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
        when(sequenceEngine.build(List.of(detection), config)).thenReturn(List.of(new SequenceRecord("CC0001", LocalDateTime.now())));

        byte[] body = reportService.buildReport();

        Path reportPath = tempDir.resolve("xlsx").resolve("sequences.xlsx");
        assertThat(Files.exists(reportPath)).isTrue();
        assertThat(Files.size(reportPath)).isEqualTo(body.length);
    }
}
