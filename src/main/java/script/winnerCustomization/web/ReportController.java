package script.winnerCustomization.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
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
    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/report/sequences.xlsx")
    public ResponseEntity<byte[]> downloadCurrentReport() throws IOException {
        log.info("HTTP GET /report/sequences.xlsx received");
        ReportService.SavedReport savedReport = reportService.saveReport();
        log.info("Saved report to {}, size={} bytes", savedReport.path(), savedReport.sizeBytes());
        return buildDownloadResponse(savedReport);
    }

    @GetMapping("/report/sequences.xlsx/{reportDate}")
    public ResponseEntity<byte[]> downloadReportForDate(
            @PathVariable
            @DateTimeFormat(pattern = "dd-MM-yyyy") LocalDate reportDate) throws IOException {
        log.info("HTTP GET /report/sequences.xlsx/{} received", reportDate);
        ReportService.SavedReport savedReport = reportService.saveReport(reportDate);
        log.info("Saved dated report to {}, date={}, size={} bytes", savedReport.path(), reportDate, savedReport.sizeBytes());
        return buildDownloadResponse(savedReport);
    }

    private ResponseEntity<byte[]> buildDownloadResponse(ReportService.SavedReport savedReport) {
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .contentLength(savedReport.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(savedReport.fileName())
                        .build()
                        .toString())
                .header("X-Saved-Report-Path", savedReport.path().toString())
                .body(savedReport.body());
    }
}
