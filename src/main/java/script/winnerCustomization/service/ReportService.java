package script.winnerCustomization.service;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final Clock clock;

    public ReportService(RuntimeConfig runtimeConfig,
                         DetectionService detectionService,
                         SequenceEngine sequenceEngine,
                         SequenceStorageService sequenceStorageService,
                         NotificationService notificationService,
                         Clock clock) {
        this.runtimeConfig = runtimeConfig;
        this.detectionService = detectionService;
        this.sequenceEngine = sequenceEngine;
        this.sequenceStorageService = sequenceStorageService;
        this.notificationService = notificationService;
        this.clock = clock;
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
        LocalDateTime reportGeneratedAt = LocalDateTime.now(clock);
        List<SequenceRecord> records = sequenceEngine.build(detections, config, reportGeneratedAt);
        attachNotifications(records, notificationService.evaluate(detections, config));
        persistSequencesAsync(records);
        byte[] bytes = toXlsx(records, reportGeneratedAt);
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

    private byte[] toXlsx(List<SequenceRecord> records, LocalDateTime reportGeneratedAt) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeSheet(workbook, workbook.createSheet("Sequences"), records, false, reportGeneratedAt);
            writeSheet(workbook, workbook.createSheet("Events"), records, true, reportGeneratedAt);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeSheet(XSSFWorkbook workbook,
                            XSSFSheet sheet,
                            List<SequenceRecord> records,
                            boolean includePlate,
                            LocalDateTime reportGeneratedAt) {
        CellStyle centeredStyle = workbook.createCellStyle();
        centeredStyle.setAlignment(HorizontalAlignment.CENTER);

        Row header = sheet.createRow(0);
        int col = 0;
        if (includePlate) {
            header.createCell(col++).setCellValue("Plate");
        }
        header.createCell(col++).setCellValue("Stage");
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
                plateRow.createCell(2).setCellValue(record.getPlateNumber());
                plateRow.getCell(2).setCellStyle(centeredStyle);
            }
            for (StageWindow stage : stages) {
                Row row = sheet.createRow(rowIndex++);
                int dataCol = 0;
                if (includePlate) {
                    row.createCell(dataCol++).setCellValue(record.getPlateNumber());
                }
                row.createCell(dataCol++).setCellValue(stage.stageLabel() + (stage.partial() ? " (partial)" : ""));
                row.createCell(dataCol++).setCellValue(format(stage.timeIn()));
                row.createCell(dataCol++).setCellValue(format(stage.timeOut()));
                row.createCell(dataCol++).setCellValue(stage.durationText(reportGeneratedAt));
                row.createCell(dataCol).setCellValue(String.join(" | ", stage.alerts()));
            }
            if (!includePlate && record.isClosed()) {
                Row closedRow = sheet.createRow(rowIndex++);
                closedRow.createCell(2).setCellValue("Sequence closed");
                closedRow.getCell(2).setCellStyle(centeredStyle);
            }
        }
        int totalColumns = includePlate ? 6 : 5;
        for (int i = 0; i < totalColumns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : REPORT_TIMESTAMP_FORMATTER.format(value);
    }
}
