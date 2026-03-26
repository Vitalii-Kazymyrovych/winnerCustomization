package script.winnerCustomization.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.service.reports.ReportService;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/report")
public class ReportController {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/sequences.xlsx")
    public ResponseEntity<byte[]> full() throws IOException {
        return asXlsx(reportService.buildReport(null));
    }

    @GetMapping("/sequences.xlsx/{day}")
    public ResponseEntity<byte[]> byDay(@PathVariable String day) throws IOException {
        LocalDate date = LocalDate.parse(day, FORMATTER);
        return asXlsx(reportService.buildReport(date));
    }

    private ResponseEntity<byte[]> asXlsx(byte[] bytes) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=sequences.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
