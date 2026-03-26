package script.winnerCustomization.service.reports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.AppConfig;
import script.winnerCustomization.model.Sequence;
import script.winnerCustomization.model.Stage;
import script.winnerCustomization.repository.SequenceStateRepository;
import script.winnerCustomization.util.DurationFormatter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ReportServiceImpl implements ReportService {
    private final SequenceStateRepository sequenceStateRepository;
    private final AppConfig appConfig;

    public ReportServiceImpl(SequenceStateRepository sequenceStateRepository, AppConfig appConfig) {
        this.sequenceStateRepository = sequenceStateRepository;
        this.appConfig = appConfig;
    }

    @Override
    public byte[] buildReport(LocalDate filterDayUtc) throws IOException {
        List<Sequence> sequences = sequenceStateRepository.findAllSequences();
        if (filterDayUtc != null) {
            LocalDateTime start = filterDayUtc.atStartOfDay(ZoneOffset.UTC).toLocalDateTime();
            LocalDateTime end = filterDayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toLocalDateTime().minusNanos(1);
            sequences = sequences.stream().filter(s -> activeOnDay(s, start, end)).toList();
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeSheet(workbook.createSheet("Sequences (Active)"), sequences.stream().filter(s -> !s.isClosed()).toList());
            writeSheet(workbook.createSheet("Sequences (Closed)"), sequences.stream().filter(Sequence::isClosed).toList());
            writeEvents(workbook.createSheet("Events"), sequences);
            workbook.write(out);
            byte[] reportBytes = out.toByteArray();
            saveReport(reportBytes, filterDayUtc);
            return reportBytes;
        }
    }

    private void writeSheet(Sheet sheet, List<Sequence> sequences) {
        int rowNum = 0;
        writeHeader(sheet, rowNum++);
        for (Sequence sequence : sequences) {
            Row plateRow = sheet.createRow(rowNum++);
            plateRow.createCell(1).setCellValue(sequence.getPlate());
            for (Stage stage : sequence.getStages()) {
                if (stage.getTimeoutSeconds() > 0) continue;
                Row r = sheet.createRow(rowNum++);
                r.createCell(0).setCellValue(stage.getLabel());
                r.createCell(1).setCellValue(stage.getInTime() == null ? "" : stage.getInTime().toString());
                r.createCell(2).setCellValue(stage.getOutTime() == null ? "" : stage.getOutTime().toString());
                r.createCell(3).setCellValue(DurationFormatter.human(stage.getDuration()));
                r.createCell(4).setCellValue(String.join(", ", stage.getAlerts()));
            }
            rowNum++;
        }
    }

    private void writeEvents(Sheet sheet, List<Sequence> sequences) {
        List<Stage> events = new ArrayList<>();
        for (Sequence sequence : sequences) events.addAll(sequence.getStages());
        events = events.stream().filter(s -> s.getTimeoutSeconds() <= 0).sorted(Comparator.comparing(Stage::getInTime,
            Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int rowNum = 0;
        writeHeader(sheet, rowNum++);
        for (Stage stage : events) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(stage.getLabel());
            row.createCell(1).setCellValue(stage.getInTime() == null ? "" : stage.getInTime().toString());
            row.createCell(2).setCellValue(stage.getOutTime() == null ? "" : stage.getOutTime().toString());
            row.createCell(3).setCellValue(DurationFormatter.human(stage.getDuration()));
            row.createCell(4).setCellValue(String.join(", ", stage.getAlerts()));
        }
    }

    private boolean activeOnDay(Sequence sequence, LocalDateTime start, LocalDateTime end) {
        LocalDateTime seqStart = sequence.getStages().stream().map(Stage::getInTime).filter(t -> t != null).min(LocalDateTime::compareTo).orElse(null);
        if (seqStart == null) return false;
        LocalDateTime seqEnd = sequence.isClosed()
            ? (sequence.getClosedAtUtc() == null ? sequence.getLastDetection() : sequence.getClosedAtUtc())
            : LocalDateTime.now(ZoneOffset.UTC);
        if (seqEnd == null) {
            seqEnd = LocalDateTime.now(ZoneOffset.UTC);
        }
        return !seqStart.isAfter(end) && !seqEnd.isBefore(start);
    }

    private void writeHeader(Sheet sheet, int rowNumber) {
        Row header = sheet.createRow(rowNumber);
        header.createCell(0).setCellValue("Stage");
        header.createCell(1).setCellValue("In Time (UTC)");
        header.createCell(2).setCellValue("Out Time (UTC)");
        header.createCell(3).setCellValue("Duration");
        header.createCell(4).setCellValue("Alerts");
    }

    private void saveReport(byte[] content, LocalDate filterDayUtc) throws IOException {
        Path dir = Path.of(appConfig.getReportsDir());
        Files.createDirectories(dir);
        String suffix = filterDayUtc == null ? "all" : filterDayUtc.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        Path file = dir.resolve("sequences_" + suffix + ".xlsx");
        Files.write(file, content);
    }
}
