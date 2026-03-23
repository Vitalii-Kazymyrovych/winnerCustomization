package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.repository.NotificationRepository;
import script.winnerCustomization.repository.SequenceRepository;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ResultsDatasetReportRegressionTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-03-22T19:24:11Z"), ZoneOffset.UTC);

    @Test
    void productionResultsDatasetKeepsStickyPostStagesCompactInReport() throws Exception {
        AppConfig config = loadConfig();
        List<Detection> detections = loadDetections();

        ReportService reportService = reportService();
        RuntimeConfig runtimeConfig = runtimeConfig(reportService);
        DetectionService detectionService = detectionService(reportService);
        when(runtimeConfig.get()).thenReturn(config);
        when(detectionService.loadAllDetections()).thenReturn(detections);

        byte[] report = reportService.buildReport();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            Map<String, List<String>> labelsByPlate = readSequenceLabels(workbook);

            assertThat(labelsByPlate.get("KA1163K")).isNotEmpty().allMatch("Post 1"::equals);
            assertThat(labelsByPlate.get("KA7828BB")).filteredOn("Post 2"::equals).isNotEmpty();
            assertThat(labelsByPlate.get("AA4444PO")).filteredOn("Post 1"::equals).isNotEmpty();
        }
    }

    private Map<String, List<String>> readSequenceLabels(XSSFWorkbook workbook) {
        Map<String, List<String>> labelsByPlate = new HashMap<>();
        String currentPlate = null;
        var sheet = workbook.getSheet("Sequences");
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            var row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            String stage = cell(row, 0);
            String outTime = cell(row, 2);
            if (stage.isBlank() && !outTime.isBlank() && !"Sequence closed".equals(outTime)) {
                currentPlate = outTime;
                labelsByPlate.putIfAbsent(currentPlate, new ArrayList<>());
                continue;
            }
            if (currentPlate != null && !stage.isBlank()) {
                labelsByPlate.get(currentPlate).add(stage);
            }
        }
        return labelsByPlate;
    }

    private String cell(org.apache.poi.ss.usermodel.Row row, int index) {
        var cell = row.getCell(index);
        return cell == null ? "" : cell.getStringCellValue();
    }

    private AppConfig loadConfig() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        AppConfig config = objectMapper.readValue(Path.of("results/config.json.production").toFile(), AppConfig.class);
        AppConfig.ReportConfig reportConfig = new AppConfig.ReportConfig();
        reportConfig.setOutputDirectory("");
        config.setReports(reportConfig);
        return config;
    }

    private List<Detection> loadDetections() throws Exception {
        List<Detection> detections = new ArrayList<>();
        boolean inCopy = false;
        for (String line : Files.readAllLines(Path.of("results/alpr_detections.sql"))) {
            if (line.startsWith("COPY ")) {
                inCopy = true;
                continue;
            }
            if (!inCopy) {
                continue;
            }
            if ("\\.".equals(line)) {
                break;
            }
            String[] parts = line.split("\\t");
            detections.add(new Detection(
                    Long.parseLong(parts[0]),
                    parts[1],
                    Integer.parseInt(parts[7]),
                    "\\N".equals(parts[12]) ? null : Integer.parseInt(parts[12]),
                    LocalDateTime.parse(parts[10].replace(' ', 'T'))
            ));
        }
        return detections;
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
        }, new TelegramNotifier(new ObjectMapper()), clock);
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
