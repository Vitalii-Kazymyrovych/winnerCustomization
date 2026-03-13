package script.winnerCustomization.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReportService {
    private final RuntimeConfig runtimeConfig;
    private final DetectionService detectionService;
    private final SequenceEngine sequenceEngine;
    private final SequenceStorageService sequenceStorageService;
    private final TelegramNotifier telegramNotifier;

    public ReportService(RuntimeConfig runtimeConfig,
                         DetectionService detectionService,
                         SequenceEngine sequenceEngine,
                         SequenceStorageService sequenceStorageService,
                         TelegramNotifier telegramNotifier) {
        this.runtimeConfig = runtimeConfig;
        this.detectionService = detectionService;
        this.sequenceEngine = sequenceEngine;
        this.sequenceStorageService = sequenceStorageService;
        this.telegramNotifier = telegramNotifier;
    }

    public byte[] buildReport() throws IOException {
        List<Detection> detections = detectionService.loadAllDetections();
        List<SequenceRecord> records = sequenceEngine.build(detections, runtimeConfig.get());
        sequenceStorageService.initialize();
        sequenceStorageService.replaceAll(records);
        sendNotifications(records);
        return toXlsx(records);
    }

    private void sendNotifications(List<SequenceRecord> records) {
        Set<String> unique = new HashSet<>();
        for (SequenceRecord r : records) {
            for (String alert : r.getAlerts()) {
                String message = "Plate " + r.getPlateNumber() + ": " + alert;
                if (unique.add(message)) {
                    telegramNotifier.sendIfEnabled(runtimeConfig.get().getNotifications(), message);
                }
            }
        }
    }

    private byte[] toXlsx(List<SequenceRecord> records) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Sequences");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Plate");
            header.createCell(1).setCellValue("Start");
            header.createCell(2).setCellValue("Finish");
            header.createCell(3).setCellValue("Path");
            header.createCell(4).setCellValue("Stage durations");
            header.createCell(5).setCellValue("Alerts");

            int rowIndex = 1;
            for (SequenceRecord r : records) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(r.getPlateNumber());
                row.createCell(1).setCellValue(String.valueOf(r.getStartedAt()));
                row.createCell(2).setCellValue(String.valueOf(r.getFinishedAt()));
                row.createCell(3).setCellValue(r.getPath());
                row.createCell(4).setCellValue(r.stageDurations());
                row.createCell(5).setCellValue(String.join(" | ", r.getAlerts()));
            }
            for (int i = 0; i <= 5; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
