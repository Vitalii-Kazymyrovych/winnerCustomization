package script.winnerCustomization.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import script.winnerCustomization.service.ReportService;

import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {
    @Mock
    private ReportService reportService;

    @InjectMocks
    private ReportController reportController;

    @Test
    void shouldReturnDefaultReportWithDefaultAttachmentName() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(reportController).build();
        when(reportService.buildReport()).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(get("/report/sequences.xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=sequences.xlsx"));

        verify(reportService).buildReport();
    }

    @Test
    void shouldReturnDateScopedReportWithDatedAttachmentName() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(reportController).build();
        LocalDate reportDate = LocalDate.of(2026, 3, 19);
        when(reportService.buildReport(reportDate)).thenReturn(new byte[]{4, 5, 6});
        when(reportService.buildDatedReportFileName(reportDate)).thenReturn("sequences-19-03-2026.xlsx");

        mockMvc.perform(get("/report/sequences.xlsx/19-03-2026"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=sequences-19-03-2026.xlsx"));

        verify(reportService).buildReport(reportDate);
    }
}
