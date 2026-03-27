package script.winnerCustomization.service.reports;
 
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.model.AlertRecord;
import script.winnerCustomization.model.PlateSequence;
import script.winnerCustomization.model.Stage;
import script.winnerCustomization.service.logic.SequenceEngineService;
 
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
 
@Service
public class ReportServiceImpl implements ReportService {
 
    private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
 
    private final ConfigLoader configLoader;
    private final SequenceEngineService sequenceEngine;
 
    public ReportServiceImpl(ConfigLoader configLoader, SequenceEngineService sequenceEngine) {
        this.configLoader = configLoader;
        this.sequenceEngine = sequenceEngine;
    }
 
    @Override
    public String generateFullReport() {
        List<PlateSequence> allSequences = sequenceEngine.getAllSequences();
        String filename = "sequences_full_" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".xlsx";
        return generateReport(allSequences, filename);
    }
 
    @Override
    public String generateDayReport(LocalDate date) {
        List<PlateSequence> allSequences = sequenceEngine.getAllSequences();
 
        // Filter: sequences active at any point during that calendar day (UTC)
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.atTime(LocalTime.MAX);
 
        List<PlateSequence> filtered = allSequences.stream()
                .filter(seq -> {
                    LocalDateTime seqStart = seq.getStartTime();
                    LocalDateTime seqEnd = seq.getCloseTime();
                    if (seqStart == null) return false;
                    // Active on that day if: start < dayEnd AND (close > dayStart OR still active)
                    boolean startBeforeDayEnd = seqStart.isBefore(dayEnd) || seqStart.isEqual(dayEnd);
                    boolean endAfterDayStart = (seqEnd == null) || seqEnd.isAfter(dayStart) || seqEnd.isEqual(dayStart);
                    return startBeforeDayEnd && endAfterDayStart;
                })
                .collect(Collectors.toList());
 
        String filename = "sequences_" + date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) + ".xlsx";
        return generateReport(filtered, filename);
    }
 
    private String generateReport(List<PlateSequence> sequences, String filename) {
        String reportsDir = configLoader.getConfig().getReportsDir();
        Path dirPath = Paths.get(reportsDir);
 
        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            log.error("Failed to create reports directory: {}", e.getMessage());
            throw new RuntimeException("Cannot create reports directory", e);
        }
 
        Path filePath = dirPath.resolve(filename);
 
        // Separate active and closed, excluding candidates
        List<PlateSequence> activeSeqs = sequences.stream()
                .filter(PlateSequence::isActive)
                .collect(Collectors.toList());
        List<PlateSequence> closedSeqs = sequences.stream()
                .filter(s -> !s.isActive())
                .collect(Collectors.toList());
 
        try (Workbook workbook = new XSSFWorkbook()) {
            // Sheet 1: Active Sequences
            createSequenceSheet(workbook, "Sequences (Active)", activeSeqs);
 
            // Sheet 2: Closed Sequences
            createSequenceSheet(workbook, "Sequences (Closed)", closedSeqs);
 
            // Sheet 3: Events
            createEventsSheet(workbook, "Events", sequences);
 
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                workbook.write(fos);
            }
 
            log.info("Report generated: {}", filePath.toAbsolutePath());
            return filePath.toAbsolutePath().toString();
 
        } catch (IOException e) {
            log.error("Failed to generate report: {}", e.getMessage());
            throw new RuntimeException("Report generation failed", e);
        }
    }
 
    private void createSequenceSheet(Workbook workbook, String sheetName, List<PlateSequence> sequences) {
        Sheet sheet = workbook.createSheet(sheetName);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle plateStyle = createPlateStyle(workbook);
 
        // Header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Stage", "In Time", "Out Time", "Duration", "Alerts"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
 
        int rowIdx = 1;
        for (PlateSequence seq : sequences) {
            // Filter out candidates
            List<Stage> visibleStages = seq.getStages().stream()
                    .filter(s -> !s.isCandidate())
                    .collect(Collectors.toList());
 
            if (visibleStages.isEmpty()) continue;
 
            // Plate header row (plate in center column)
            Row plateRow = sheet.createRow(rowIdx++);
            Cell plateCell = plateRow.createCell(2); // center column
            plateCell.setCellValue(seq.getPlateNumber());
            plateCell.setCellStyle(plateStyle);
 
            // Stage rows
            for (Stage stage : visibleStages) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(stage.getLabel());
                row.createCell(1).setCellValue(formatTimestamp(stage.getInTime()));
                row.createCell(2).setCellValue(formatTimestamp(stage.getOutTime()));
                row.createCell(3).setCellValue(formatDuration(stage.getDurationSeconds()));
                row.createCell(4).setCellValue(formatAlerts(stage.getAlerts()));
            }
        }
 
        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }
 
    private void createEventsSheet(Workbook workbook, String sheetName, List<PlateSequence> sequences) {
        Sheet sheet = workbook.createSheet(sheetName);
        CellStyle headerStyle = createHeaderStyle(workbook);
 
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Plate", "Stage", "In Time", "Out Time", "Duration", "Alerts"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
 
        // Collect all stages (non-candidate) across all sequences
        List<Stage> allStages = new ArrayList<>();
        for (PlateSequence seq : sequences) {
            for (Stage s : seq.getStages()) {
                if (!s.isCandidate()) {
                    allStages.add(s);
                }
            }
        }
 
        // Sort by inTime descending (newest first), nulls last
        allStages.sort((a, b) -> {
            if (a.getInTime() == null && b.getInTime() == null) return 0;
            if (a.getInTime() == null) return 1;
            if (b.getInTime() == null) return -1;
            return b.getInTime().compareTo(a.getInTime());
        });
 
        int rowIdx = 1;
        for (Stage stage : allStages) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(stage.getPlateNumber());
            row.createCell(1).setCellValue(stage.getLabel());
            row.createCell(2).setCellValue(formatTimestamp(stage.getInTime()));
            row.createCell(3).setCellValue(formatTimestamp(stage.getOutTime()));
            row.createCell(4).setCellValue(formatDuration(stage.getDurationSeconds()));
            row.createCell(5).setCellValue(formatAlerts(stage.getAlerts()));
        }
 
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }
 
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
 
    private CellStyle createPlateStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        return style;
    }
 
    private String formatTimestamp(LocalDateTime dt) {
        if (dt == null) return "";
        return dt.format(TIMESTAMP_FMT);
    }
 
    private String formatDuration(Long durationSeconds) {
        if (durationSeconds == null) return "";
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
 
    private String formatAlerts(List<AlertRecord> alerts) {
        if (alerts == null || alerts.isEmpty()) return "";
        return alerts.stream()
                .map(AlertRecord::getMessage)
                .collect(Collectors.joining(", "));
    }
}