package script.winnerCustomization.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final DateTimeFormatter REPORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter REPORT_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RuntimeConfig runtimeConfig;
    private final DetectionService detectionService;
    private final SequenceEngine sequenceEngine;
    private final SequenceStorageService sequenceStorageService;
    private final NotificationService notificationService;

    public ReportService(RuntimeConfig runtimeConfig,
                         DetectionService detectionService,
                         SequenceEngine sequenceEngine,
                         SequenceStorageService sequenceStorageService,
                         NotificationService notificationService) {
        this.runtimeConfig = runtimeConfig;
        this.detectionService = detectionService;
        this.sequenceEngine = sequenceEngine;
        this.sequenceStorageService = sequenceStorageService;
        this.notificationService = notificationService;
    }

    public byte[] buildReport() throws IOException {
        AppConfig config = runtimeConfig.get();
        List<Detection> detections = detectionService.loadAllDetections();
        return buildReport(config, detections, "sequences.xlsx");
    }

    public byte[] buildReport(LocalDate reportDate) throws IOException {
        AppConfig config = runtimeConfig.get();
        LocalDateTime fromInclusive = reportDate.atStartOfDay();
        LocalDateTime toExclusive = reportDate.plusDays(1).atStartOfDay();
        return buildReport(config, detectionService.loadDetectionsBetween(fromInclusive, toExclusive), buildDatedReportFileName(reportDate));
    }

    public String buildDatedReportFileName(LocalDate reportDate) {
        return "sequences-" + REPORT_DATE_FORMATTER.format(reportDate) + ".xlsx";
    }

    private byte[] buildReport(AppConfig config, List<Detection> detections, String fileName) throws IOException {
        List<SequenceRecord> records = sequenceEngine.build(detections, config);
        attachNotifications(records, notificationService.evaluate(detections, config));
        persistSequencesAsync(records);
        byte[] bytes = toXlsx(records);
        persistReportFile(bytes, config, fileName);
        return bytes;
    }

    private void attachNotifications(List<SequenceRecord> records, List<SequenceRecord.NotificationEvent> notifications) {
        for (SequenceRecord.NotificationEvent notification : notifications) {
            for (SequenceRecord record : records) {
                if (!record.getPlateNumber().equals(notification.plateNumber())) {
                    continue;
                }
                if (record.getStartedAt() != null && !notification.triggeredAt().isBefore(record.getStartedAt())
                        && (record.getFinishedAt() == null || !notification.triggeredAt().isAfter(record.getFinishedAt()))) {
                    record.addNotification(notification);
                    StageWindow stage = record.stagesChronologically().stream()
                            .filter(item -> item.overlaps(notification.triggeredAt()))
                            .findFirst()
                            .orElse(null);
                    if (stage != null) {
                        stage.addAlert(notification.message());
                    }
                }
            }
        }
    }

    private void persistSequencesAsync(List<SequenceRecord> records) {
        CompletableFuture.runAsync(() -> {
            try {
                sequenceStorageService.initialize();
                sequenceStorageService.replaceAll(records);
            } catch (Exception exception) {
                log.warn("Async sequence persistence failed: {}", exception.getMessage());
            }
        });
    }

    private void persistReportFile(byte[] reportBytes, AppConfig config, String fileName) {
        String outputDirectory = config.getReports() == null ? null : config.getReports().getOutputDirectory();
        if (outputDirectory == null || outputDirectory.isBlank()) {
            return;
        }
        try {
            Path directory = Path.of(outputDirectory);
            Files.createDirectories(directory);
            Files.write(directory.resolve(fileName), reportBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist report to " + outputDirectory, exception);
        }
    }

    private byte[] toXlsx(List<SequenceRecord> records) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeSheet(workbook.createSheet("Sequences"), records, false);
            writeSheet(workbook.createSheet("Events"), records, true);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeSheet(XSSFSheet sheet, List<SequenceRecord> records, boolean includePlate) {
        Row header = sheet.createRow(0);
        int col = 0;
        if (includePlate) {
            header.createCell(col++).setCellValue("Plate");
        }
        header.createCell(col++).setCellValue("Stage");
        header.createCell(col++).setCellValue("Type");
        header.createCell(col++).setCellValue("In time");
        header.createCell(col++).setCellValue("Out time");
        header.createCell(col++).setCellValue("Duration");
        header.createCell(col).setCellValue("Alerts");

        int rowIndex = 1;
        for (SequenceRecord record : records) {
            List<StageWindow> stages = record.stagesChronologically();
            if (stages.isEmpty()) {
                continue;
            }
            if (!includePlate) {
                Row plateRow = sheet.createRow(rowIndex++);
                plateRow.createCell(0).setCellValue(record.getPlateNumber());
            }
            for (StageWindow stage : stages) {
                Row row = sheet.createRow(rowIndex++);
                int dataCol = 0;
                if (includePlate) {
                    row.createCell(dataCol++).setCellValue(record.getPlateNumber());
                }
                row.createCell(dataCol++).setCellValue(stage.stageLabel() + (stage.partial() ? " (partial)" : ""));
                row.createCell(dataCol++).setCellValue(stage.stageType().name());
                row.createCell(dataCol++).setCellValue(format(stage.timeIn()));
                row.createCell(dataCol++).setCellValue(format(stage.timeOut()));
                row.createCell(dataCol++).setCellValue(stage.durationText(record.getFinishedAt()));
                row.createCell(dataCol).setCellValue(String.join(" | ", stage.alerts()));
            }
        }
        for (int i = 0; i < 7; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : REPORT_TIMESTAMP_FORMATTER.format(value);
    }
}
