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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final DateTimeFormatter REPORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final RuntimeConfig runtimeConfig;
    private final DetectionService detectionService;
    private final SequenceEngine sequenceEngine;
    private final SequenceStorageService sequenceStorageService;

    public ReportService(RuntimeConfig runtimeConfig,
                         DetectionService detectionService,
                         SequenceEngine sequenceEngine,
                         SequenceStorageService sequenceStorageService) {
        this.runtimeConfig = runtimeConfig;
        this.detectionService = detectionService;
        this.sequenceEngine = sequenceEngine;
        this.sequenceStorageService = sequenceStorageService;
    }

    public byte[] buildReport() throws IOException {
        log.info("Report build started for all available detections");
        AppConfig config = runtimeConfig.get();
        List<Detection> detections = detectionService.loadAllDetections();
        return buildReport(config, detections, "sequences.xlsx");
    }

    public byte[] buildReport(LocalDate reportDate) throws IOException {
        LocalDateTime fromInclusive = reportDate.atStartOfDay();
        LocalDateTime toExclusive = reportDate.plusDays(1).atStartOfDay();
        String reportFileName = buildDatedReportFileName(reportDate);
        log.info("Report build started for date {} (from={} toExclusive={})", reportDate, fromInclusive, toExclusive);
        AppConfig config = runtimeConfig.get();
        List<Detection> detections = detectionService.loadDetectionsBetween(fromInclusive, toExclusive);
        return buildReport(config, detections, reportFileName);
    }

    public String buildDatedReportFileName(LocalDate reportDate) {
        return "sequences-" + REPORT_DATE_FORMATTER.format(reportDate) + ".xlsx";
    }

    private byte[] buildReport(AppConfig config, List<Detection> detections, String reportFileName) throws IOException {
        log.info("Building sequences from {} detections", detections.size());
        List<SequenceRecord> records = sequenceEngine.build(detections, config);
        log.info("Built {} sequence records", records.size());
        persistSequencesAsync(records);
        byte[] reportBytes = toXlsx(records);
        persistReportFile(reportBytes, config, reportFileName);
        log.info("Report build finished, fileName={}, bytes={}", reportFileName, reportBytes.length);
        return reportBytes;
    }

    private void persistSequencesAsync(List<SequenceRecord> records) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Async sequence persistence started for {} records", records.size());
                sequenceStorageService.initialize();
                sequenceStorageService.replaceAll(records);
                log.info("Async sequence persistence finished");
            } catch (Exception exception) {
                log.warn("Async sequence persistence failed: {}", exception.getMessage());
            }
        });
    }

    private void persistReportFile(byte[] reportBytes, AppConfig config, String reportFileName) {
        String outputDirectory = config.getReports() == null ? null : config.getReports().getOutputDirectory();
        if (outputDirectory == null || outputDirectory.isBlank()) {
            log.info("Report output directory is not configured, skipping report file persistence");
            return;
        }
        Path outputPath = Path.of(outputDirectory);
        Path filePath = outputPath.resolve(reportFileName);
        try {
            Files.createDirectories(outputPath);
            Files.write(filePath, reportBytes);
            log.info("Report file persisted to {}", filePath.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist report to configured directory: " + outputPath, exception);
        }
    }

    private byte[] toXlsx(List<SequenceRecord> records) throws IOException {
        log.info("Generating XLSX report for {} records", records.size());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeStageSheet(workbook, records);
            writeFlatStageSheet(workbook, records);
            workbook.write(output);
            log.info("XLSX generation completed");
            return output.toByteArray();
        }
    }

    private void writeStageSheet(XSSFWorkbook workbook, List<SequenceRecord> records) {
        XSSFSheet sheet = workbook.createSheet("Sequences");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Stage");
        header.createCell(1).setCellValue("Time in");
        header.createCell(2).setCellValue("Time out");
        header.createCell(3).setCellValue("Duration");
        header.createCell(4).setCellValue("Alerts");

        int rowIndex = 1;
        for (SequenceRecord r : records) {
            List<StageLine> stages = toStages(r);
            if (stages.isEmpty()) {
                continue;
            }

            Row plateRow = sheet.createRow(rowIndex++);
            plateRow.createCell(2).setCellValue(r.getPlateNumber());
            for (StageLine stage : stages) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(stage.stageName());
                row.createCell(1).setCellValue(formatTime(stage.timeIn()));
                row.createCell(2).setCellValue(formatTime(stage.timeOut()));
                row.createCell(3).setCellValue(formatDuration(stage.timeIn(), stage.timeOut()));
                row.createCell(4).setCellValue(stage.alert());
            }
        }
        for (int i = 0; i <= 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeFlatStageSheet(XSSFWorkbook workbook, List<SequenceRecord> records) {
        XSSFSheet sheet = workbook.createSheet("Events");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Plate");
        header.createCell(1).setCellValue("Stage");
        header.createCell(2).setCellValue("In time");
        header.createCell(3).setCellValue("Out time");
        header.createCell(4).setCellValue("Duration");
        header.createCell(5).setCellValue("Alarms");

        int rowIndex = 1;
        for (SequenceRecord record : records) {
            for (StageLine stage : toStages(record)) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.getPlateNumber());
                row.createCell(1).setCellValue(stage.stageName());
                row.createCell(2).setCellValue(formatTime(stage.timeIn()));
                row.createCell(3).setCellValue(formatTime(stage.timeOut()));
                row.createCell(4).setCellValue(formatDuration(stage.timeIn(), stage.timeOut()));
                row.createCell(5).setCellValue(stage.alert());
            }
        }

        for (int i = 0; i <= 5; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private List<StageLine> toStages(SequenceRecord record) {
        return record.stagesChronologically().stream()
                .map(stage -> new StageLine(stage.reportLabel(), stage.timeIn(), stage.timeOut(), stage.alert()))
                .toList();
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String formatDuration(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return "";
        }
        Duration duration = Duration.between(from, to);
        long seconds = duration.toSeconds();
        long abs = Math.abs(seconds);
        String formatted = String.format("%02d:%02d:%02d", abs / 3600, (abs % 3600) / 60, abs % 60);
        return seconds < 0 ? "-" + formatted : formatted;
    }

    private record StageLine(String stageName, LocalDateTime timeIn, LocalDateTime timeOut, String alert) {
    }
}
