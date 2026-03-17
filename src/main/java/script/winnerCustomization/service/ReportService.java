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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

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
        log.info("Report build started");
        AppConfig config = runtimeConfig.get();
        List<Detection> detections = detectionService.loadAllDetections();
        log.info("Building sequences from {} detections", detections.size());
        List<SequenceRecord> records = sequenceEngine.build(detections, config);
        log.info("Built {} sequence records", records.size());
        persistSequencesAsync(records);
        byte[] reportBytes = toXlsx(records);
        persistReportFile(reportBytes, config);
        log.info("Report build finished, bytes={}", reportBytes.length);
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

    private void persistReportFile(byte[] reportBytes, AppConfig config) {
        String outputDirectory = config.getReports() == null ? null : config.getReports().getOutputDirectory();
        if (outputDirectory == null || outputDirectory.isBlank()) {
            log.info("Report output directory is not configured, skipping report file persistence");
            return;
        }
        Path outputPath = Path.of(outputDirectory);
        Path filePath = outputPath.resolve("sequences.xlsx");
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
            writeEventSheet(workbook, records);
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
            Row plateRow = sheet.createRow(rowIndex++);
            plateRow.createCell(2).setCellValue(r.getPlateNumber());

            List<StageLine> stages = toStages(r);
            List<String> alerts = r.getAlerts();
            for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                StageLine stage = stages.get(stageIndex);
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(stage.stageName());
                row.createCell(1).setCellValue(formatTime(stage.timeIn()));
                row.createCell(2).setCellValue(formatTime(stage.timeOut()));
                row.createCell(3).setCellValue(formatDuration(stage.timeIn(), stage.timeOut()));

                String alertCellValue = "";
                if (alerts.isEmpty() && stageIndex == 0) {
                    alertCellValue = "none";
                } else if (stageIndex < alerts.size()) {
                    alertCellValue = alerts.get(stageIndex);
                }
                row.createCell(4).setCellValue(alertCellValue);
            }
        }
        for (int i = 0; i <= 4; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeEventSheet(XSSFWorkbook workbook, List<SequenceRecord> records) {
        XSSFSheet sheet = workbook.createSheet("Events");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Номер");
        header.createCell(1).setCellValue("Камера");
        header.createCell(2).setCellValue("Этап");
        header.createCell(3).setCellValue("Тип события (In \\ Out)");
        header.createCell(4).setCellValue("Время");
        header.createCell(5).setCellValue("Для события Out время проведенное на этапе");

        int rowIndex = 1;
        for (SequenceRecord record : records) {
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Drive in", "Drive in", "In", record.getStartedAt(), null, null);
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Drive in", "Drive in", "Out", record.getDriveInOutAt(), record.getStartedAt(), record.getDriveInOutAt());
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Service", "Service", "In", record.getServiceInAt(), null, null);
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Service post", "Post", "In", record.getPostInAt(), null, null);
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Service post", "Post", "Out", record.getPostOutAt(), record.getPostInAt(), record.getPostOutAt());
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Service", "Service", "Out", record.getServiceOutAt(), resolveServiceOutStart(record), record.getServiceOutAt());
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Parking", "Parking", "In", record.getParkingInAt(), null, null);
            rowIndex = addEventRow(sheet, rowIndex, record.getPlateNumber(), "Parking", "Parking", "Out", record.getParkingOutAt(), record.getParkingInAt(), record.getParkingOutAt());
        }

        for (int i = 0; i <= 5; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private int addEventRow(XSSFSheet sheet,
                            int rowIndex,
                            String plate,
                            String camera,
                            String stage,
                            String eventType,
                            LocalDateTime eventTime,
                            LocalDateTime durationStart,
                            LocalDateTime durationEnd) {
        if (eventTime == null) {
            return rowIndex;
        }
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(plate);
        row.createCell(1).setCellValue(camera);
        row.createCell(2).setCellValue(stage);
        row.createCell(3).setCellValue(eventType);
        row.createCell(4).setCellValue(formatTime(eventTime));
        row.createCell(5).setCellValue("Out".equals(eventType) ? formatDuration(durationStart, durationEnd) : "");
        return rowIndex + 1;
    }

    private LocalDateTime resolveServiceOutStart(SequenceRecord record) {
        if (record.getSecondServiceInAt() != null) {
            return record.getSecondServiceInAt();
        }
        if (record.getPostOutAt() != null) {
            return record.getPostOutAt();
        }
        return record.getServiceInAt();
    }

    private List<StageLine> toStages(SequenceRecord record) {
        List<StageLine> stages = new ArrayList<>();
        addStage(stages, "Drive in", record.getStartedAt(), record.getDriveInOutAt());
        addStage(stages, "Service", record.getServiceInAt(), record.getServiceFirstFinishedAt());
        addStage(stages, "Post", record.getPostInAt(), record.getPostOutAt());
        addStage(stages, "Service", record.getSecondServiceInAt(), record.getServiceOutAt());
        addStage(stages, "Parking", record.getParkingInAt(), record.getParkingOutAt());
        if (stages.isEmpty()) {
            stages.add(new StageLine("No stages", record.getStartedAt(), record.getFinishedAt()));
        }
        return stages;
    }


    private void addStage(List<StageLine> stages, String stageName, LocalDateTime timeIn, LocalDateTime timeOut) {
        if (timeIn == null && timeOut == null) {
            return;
        }
        stages.add(new StageLine(stageName, timeIn, timeOut));
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

    private record StageLine(String stageName, LocalDateTime timeIn, LocalDateTime timeOut) {
    }
}
