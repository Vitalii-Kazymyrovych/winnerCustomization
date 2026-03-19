package script.winnerCustomization.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.service.ReportService;

import java.io.IOException;
import java.time.LocalDate;

@RestController
public class ReportController {
    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/report/sequences.xlsx")
    public ResponseEntity<byte[]> downloadReport() throws IOException {
        log.info("HTTP GET /report/sequences.xlsx received");
        byte[] body = reportService.buildReport();
        log.info("Returning report response, size={} bytes", body.length);
        return responseWithAttachment(body, "sequences.xlsx");
    }

    @GetMapping("/report/sequences.xlsx/{reportDate}")
    public ResponseEntity<byte[]> downloadReportForDate(
            @PathVariable
            @DateTimeFormat(pattern = "dd-MM-yyyy") LocalDate reportDate) throws IOException {
        log.info("HTTP GET /report/sequences.xlsx/{} received", reportDate);
        byte[] body = reportService.buildReport(reportDate);
        String fileName = reportService.buildDatedReportFileName(reportDate);
        log.info("Returning dated report response, date={}, size={} bytes", reportDate, body.length);
        return responseWithAttachment(body, fileName);
    }

    private ResponseEntity<byte[]> responseWithAttachment(byte[] body, String fileName) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
