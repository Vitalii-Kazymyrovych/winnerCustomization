package script.winnerCustomization.report;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.model.SequenceRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class SequenceReportWriter {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] write(StageSequenceProcessor.ProcessingResult result, LocalDateTime reportAt) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            var sequencesSheet = workbook.createSheet("Sequences");
            var eventsSheet = workbook.createSheet("Events");
            writeSequenceSheet(sequencesSheet, result.sequences(), reportAt);
            writeEventsSheet(eventsSheet, result.sequences(), reportAt);
            autosize(sequencesSheet, 5);
            autosize(eventsSheet, 6);
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void writeSequenceSheet(org.apache.poi.ss.usermodel.Sheet sheet, java.util.List<SequenceRecord> sequences, LocalDateTime reportAt) {
        int rowIndex = 0;
        createRow(sheet.createRow(rowIndex++), "Stage", "In time", "Out time", "Duration", "Alerts");
        for (SequenceRecord sequence : sequences) {
            Row plateRow = sheet.createRow(rowIndex++);
            plateRow.createCell(0).setCellValue(sequence.getPlateNumber());
            for (SequenceRecord.StageWindow stage : sequence.stagesChronologically()) {
                createRow(sheet.createRow(rowIndex++),
                        stage.reportLabel(),
                        format(stage.timeIn()),
                        format(stage.timeOut()),
                        format(stage.durationAt(reportAt)),
                        String.join(" | ", stage.alerts()));
            }
            Row closedRow = sheet.createRow(rowIndex++);
            closedRow.createCell(0).setCellValue("Sequence closed");
        }
    }

    private void writeEventsSheet(org.apache.poi.ss.usermodel.Sheet sheet, java.util.List<SequenceRecord> sequences, LocalDateTime reportAt) {
        int rowIndex = 0;
        createRow(sheet.createRow(rowIndex++), "Plate", "Stage", "In time", "Out time", "Duration", "Alerts");
        for (SequenceRecord sequence : sequences) {
            for (SequenceRecord.StageWindow stage : sequence.stagesChronologically()) {
                createRow(sheet.createRow(rowIndex++),
                        sequence.getPlateNumber(),
                        stage.reportLabel(),
                        format(stage.timeIn()),
                        format(stage.timeOut()),
                        format(stage.durationAt(reportAt)),
                        String.join(" | ", stage.alerts()));
            }
        }
    }

    private void createRow(Row row, String... values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
    }

    private void autosize(org.apache.poi.ss.usermodel.Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : DATE_TIME_FORMATTER.format(value);
    }

    private String format(Duration duration) {
        if (duration == null) {
            return "";
        }
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }
}
