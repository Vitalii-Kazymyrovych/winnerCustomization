package script.winnerCustomization.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        List<Detection> detections = detectionService.loadAllDetections();
        log.info("Building sequences from {} detections", detections.size());
        List<SequenceRecord> records = sequenceEngine.build(detections, runtimeConfig.get());
        log.info("Built {} sequence records", records.size());
        persistSequencesAsync(records);
        byte[] reportBytes = toXlsx(records);
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

    private byte[] toXlsx(List<SequenceRecord> records) throws IOException {
        log.info("Generating XLSX report for {} records", records.size());
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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
            workbook.write(output);
            log.info("XLSX generation completed");
            return output.toByteArray();
        }
    }

    private List<StageLine> toStages(SequenceRecord record) {
        List<StageLine> stages = new ArrayList<>();
        addStage(stages, "Drive in", record.getStartedAt(), record.getDriveInOutAt());
        addStage(stages, "Service", record.getServiceInAt(), resolveServiceStageEnd(record));
        addStage(stages, "Post", record.getPostInAt(), record.getServiceOutAt());
        addStage(stages, "Parking", record.getParkingInAt(), record.getParkingOutAt());
        if (stages.isEmpty()) {
            stages.add(new StageLine("No stages", record.getStartedAt(), record.getFinishedAt()));
        }
        return stages;
    }

    private LocalDateTime resolveServiceStageEnd(SequenceRecord record) {
        if (record.getPostInAt() != null) {
            return record.getPostInAt();
        }
        return record.getServiceOutAt();
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
