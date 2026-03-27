package script.winnerCustomization.controller;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.service.reports.ReportService;
 
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
 
@RestController
@RequestMapping("/report")
public class ReportController {
 
    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");
 
    private final ReportService reportService;
 
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }
 
    @GetMapping("/sequences.xlsx")
    public ResponseEntity<Resource> getFullReport() {
        log.info("Generating full report...");
        String filePath = reportService.generateFullReport();
        return buildFileResponse(filePath, "sequences.xlsx");
    }
 
    @GetMapping("/sequences.xlsx/{date}")
    public ResponseEntity<Resource> getDayReport(@PathVariable String date) {
        log.info("Generating day report for: {}", date);
        try {
            LocalDate localDate = LocalDate.parse(date, DATE_FMT);
            String filePath = reportService.generateDayReport(localDate);
            return buildFileResponse(filePath, "sequences_" + date + ".xlsx");
        } catch (DateTimeParseException e) {
            log.error("Invalid date format: {}. Expected dd-MM-yyyy", date);
            return ResponseEntity.badRequest().build();
        }
    }
 
    private ResponseEntity<Resource> buildFileResponse(String filePath, String downloadFilename) {
        File file = new File(filePath);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
 
        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFilename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }
}