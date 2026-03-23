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
    void datedReportReprocessesFullHistoryBeforeFilteringDayWindow() throws Exception {
        InMemoryDetectionRepository repository = new InMemoryDetectionRepository(List.of(
                new Detection(1, "AA1111", 3001, null, java.time.LocalDateTime.of(2026, 3, 22, 23, 55)),
                new Detection(2, "AA1111", 3001, null, java.time.LocalDateTime.of(2026, 3, 23, 0, 10)),
                new Detection(3, "AA1111", 1001, 90, java.time.LocalDateTime.of(2026, 3, 23, 0, 20))
        ));
        ReportService service = new ReportService(
                repository,
                runtimeConfig(TestConfigFactory.standardConfig()),
                new StageSequenceProcessor(),
                new SequenceReportWriter(),
                Clock.fixed(Instant.parse("2026-03-24T00:00:00Z"), ZoneOffset.UTC));

        byte[] report = service.buildReport(LocalDate.of(2026, 3, 23));

        assertThat(repository.findAllCalled).isTrue();
        assertThat(repository.findBetweenCalled).isFalse();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
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

    private List<String> readRow(Row row) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var cell = row.getCell(i);
            values.add(cell == null ? "" : cell.getStringCellValue());
        }
        return values;
    }

    private RuntimeConfig runtimeConfig(AppConfig config) {
        RuntimeConfig runtimeConfig = new RuntimeConfig(new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(runtimeConfig, "appConfig", new java.util.concurrent.atomic.AtomicReference<>(config));
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
