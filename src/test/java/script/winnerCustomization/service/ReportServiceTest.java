package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.config.TestConfigFactory;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.report.SequenceReportWriter;
import script.winnerCustomization.repository.DetectionRepository;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServiceTest {

    @Test
    void datedReportReprocessesFullHistoryBeforeFilteringDayWindowAndSavesFile() throws Exception {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository(List.of(
                new Detection(1, "AA1111", 3001, null, java.time.LocalDateTime.of(2026, 3, 22, 23, 55)),
                new Detection(2, "AA1111", 3001, null, java.time.LocalDateTime.of(2026, 3, 23, 0, 10)),
                new Detection(3, "AA1111", 1001, 90, java.time.LocalDateTime.of(2026, 3, 23, 0, 20))
        ));
        AppConfig config = TestConfigFactory.standardConfig();
        Path outputDirectory = Files.createTempDirectory("dated-report-output");
        config.getReports().setOutputDirectory(outputDirectory.toString());

        ReportService service = new ReportService(
                repository,
                runtimeConfig(config),
                new StageSequenceProcessor(),
                new SequenceReportWriter(),
                Clock.fixed(Instant.parse("2026-03-24T00:00:00Z"), ZoneOffset.UTC));

        ReportService.SavedReport savedReport = service.saveReport(LocalDate.of(2026, 3, 23));

        assertThat(repository.findAllCalled).isTrue();
        assertThat(repository.findBetweenCalled).isFalse();
        assertThat(savedReport.fileName()).isEqualTo("sequences-2026-03-23.xlsx");
        assertThat(savedReport.path()).isEqualTo(outputDirectory.resolve("sequences-2026-03-23.xlsx"));
        assertThat(savedReport.path()).exists();
        assertThat(savedReport.sizeBytes()).isGreaterThan(0);
        assertThat(savedReport.body()).isEqualTo(Files.readAllBytes(savedReport.path()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(savedReport.path()))) {
            List<List<String>> rows = new ArrayList<>();
            workbook.getSheet("Events").forEach(row -> rows.add(readRow(row)));
            assertThat(rows).anySatisfy(row -> {
                assertThat(row.get(1)).isEqualTo("Post 1");
                assertThat(row.get(2)).isEqualTo("2026-03-22 23:55:00");
                assertThat(row.get(3)).isEqualTo("2026-03-23 00:10:00");
            });
            assertThat(rows).anySatisfy(row -> assertThat(row.get(1)).isEqualTo("Drive In"));
        }
    }

    @Test
    void currentReportUsesConfiguredRelativeOutputDirectoryNearConfigJson() throws Exception {
        AppConfig config = TestConfigFactory.standardConfig();
        config.getReports().setOutputDirectory("reports-out");
        Path configDirectory = Files.createTempDirectory("runtime-config-dir");

        ReportService service = new ReportService(
                new InMemoryDetectionRepository(List.of()),
                runtimeConfig(config, configDirectory.resolve("config.json")),
                new StageSequenceProcessor(),
                new SequenceReportWriter(),
                Clock.fixed(Instant.parse("2026-03-24T00:00:00Z"), ZoneOffset.UTC));

        ReportService.SavedReport savedReport = service.saveReport();

        assertThat(savedReport.fileName()).isEqualTo("sequences.xlsx");
        assertThat(savedReport.path()).isEqualTo(configDirectory.resolve("reports-out").resolve("sequences.xlsx"));
        assertThat(savedReport.path()).exists();
        assertThat(savedReport.sizeBytes()).isGreaterThan(0);
        assertThat(savedReport.body()).isEqualTo(Files.readAllBytes(savedReport.path()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(savedReport.body()))) {
            assertThat(workbook.getSheet("Sequences")).isNotNull();
            assertThat(workbook.getSheet("Events")).isNotNull();
        }
    }

    private List<String> readRow(Row row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var cell = row.getCell(i);
            values.add(cell == null ? "" : cell.getStringCellValue());
        }
        return values;
    }

    private RuntimeConfig runtimeConfig(AppConfig config) {
        return runtimeConfig(config, Path.of(System.getProperty("user.dir"), "config.json"));
    }

    private RuntimeConfig runtimeConfig(AppConfig config, Path configPath) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(runtimeConfig, "appConfig", new java.util.concurrent.atomic.AtomicReference<>(config));
        ReflectionTestUtils.setField(runtimeConfig, "configPath", configPath);
        return runtimeConfig;
    }

    private static final class InMemoryDetectionRepository implements DetectionRepository {
        private final List<Detection> detections;
        private final AtomicBoolean findAllCalled = new AtomicBoolean(false);
        private final AtomicBoolean findBetweenCalled = new AtomicBoolean(false);

        private InMemoryDetectionRepository(List<Detection> detections) {
            this.detections = detections;
        }

        @Override
        public List<Detection> findAll() {
            findAllCalled.set(true);
            return detections;
        }

        @Override
        public List<Detection> findBetween(java.time.LocalDateTime fromInclusive, java.time.LocalDateTime toExclusive) {
            findBetweenCalled.set(true);
            return detections.stream()
                    .filter(detection -> !detection.createdAt().isBefore(fromInclusive) && detection.createdAt().isBefore(toExclusive))
                    .toList();
        }
    }
}
