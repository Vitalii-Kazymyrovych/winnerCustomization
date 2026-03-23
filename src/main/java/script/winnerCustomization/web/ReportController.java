package script.winnerCustomization.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.service.ReportService;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

@RestController
public class ReportController {
    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/report/sequences.xlsx")
    public ResponseEntity<Map<String, Object>> triggerReportSave() throws IOException {
        log.info("HTTP GET /report/sequences.xlsx received");
        ReportService.SavedReport savedReport = reportService.saveReport();
        log.info("Saved report to {}, size={} bytes", savedReport.path(), savedReport.sizeBytes());
        return ResponseEntity.ok(Map.of(
                "status", "SAVED",
                "path", savedReport.path().toString(),
                "sizeBytes", savedReport.sizeBytes()
        ));
    }

    @GetMapping("/report/sequences.xlsx/{reportDate}")
    public ResponseEntity<Map<String, Object>> triggerReportSaveForDate(
            @PathVariable
            @DateTimeFormat(pattern = "dd-MM-yyyy") LocalDate reportDate) throws IOException {
        log.info("HTTP GET /report/sequences.xlsx/{} received", reportDate);
        ReportService.SavedReport savedReport = reportService.saveReport(reportDate);
        log.info("Saved dated report to {}, date={}, size={} bytes", savedReport.path(), reportDate, savedReport.sizeBytes());
        return ResponseEntity.ok(Map.of(
                "status", "SAVED",
                "reportDate", reportDate.toString(),
                "path", savedReport.path().toString(),
                "sizeBytes", savedReport.sizeBytes()
        ));
    }
}
