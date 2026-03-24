package script.winnerCustomization.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import script.winnerCustomization.service.ReportService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportControllerTest {

    @Test
    void currentReportEndpointDownloadsAttachmentAndKeepsSavedPathHeader() throws Exception {
        ReportService reportService = mock(ReportService.class);
        byte[] body = "current-report".getBytes(StandardCharsets.UTF_8);
        ReportService.SavedReport savedReport = new ReportService.SavedReport(
                "sequences.xlsx",
                Path.of("/tmp/reports/sequences.xlsx"),
                body.length,
                body);
        when(reportService.saveReport()).thenReturn(savedReport);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ReportController(reportService)).build();

        mockMvc.perform(get("/report/sequences.xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sequences.xlsx\""))
                .andExpect(header().string("X-Saved-Report-Path", "/tmp/reports/sequences.xlsx"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, body.length))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(body));

        verify(reportService).saveReport();
    }

    @Test
    void datedReportEndpointDownloadsAttachmentAndUsesRequestedDate() throws Exception {
        ReportService reportService = mock(ReportService.class);
        byte[] body = "dated-report".getBytes(StandardCharsets.UTF_8);
        ReportService.SavedReport savedReport = new ReportService.SavedReport(
                "sequences-2026-03-23.xlsx",
                Path.of("/tmp/reports/sequences-2026-03-23.xlsx"),
                body.length,
                body);
        when(reportService.saveReport(LocalDate.of(2026, 3, 23))).thenReturn(savedReport);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ReportController(reportService)).build();

        mockMvc.perform(get("/report/sequences.xlsx/23-03-2026"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sequences-2026-03-23.xlsx\""))
                .andExpect(header().string("X-Saved-Report-Path", "/tmp/reports/sequences-2026-03-23.xlsx"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, body.length))
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(content().bytes(body));

        verify(reportService).saveReport(LocalDate.of(2026, 3, 23));
    }
}
