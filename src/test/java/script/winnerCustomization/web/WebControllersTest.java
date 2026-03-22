package script.winnerCustomization.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.config.TestFixtures;
import script.winnerCustomization.service.ReportService;
import script.winnerCustomization.service.SourcePullTriggerService;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class WebControllersTest {
    @Test
    void configControllerSupportsJsonHtmlAndFormSave() throws Exception {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        var config = TestFixtures.configWithReportDirectory("");
        when(runtimeConfig.get()).thenReturn(config);
        when(runtimeConfig.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        ConfigController controller = new ConfigController(runtimeConfig, new ObjectMapper().findAndRegisterModules());

        assertThat(controller.getConfig()).isSameAs(config);
        assertThat(controller.getConfigHtml().getBody()).contains("Runtime configuration");
        assertThat(controller.updateConfig(config)).isSameAs(config);
        assertThat(controller.updateConfigForm("{\"sequenceCloseTimeoutMinutes\":10,\"sourceDatabase\":{\"schema\":\"videoanalytics\"},\"sourceTable\":{\"table\":\"alpr_detections\"}}", "application/json").getHeaders().getContentType().toString())
                .isEqualTo("application/json");
        assertThat(controller.updateConfigForm(new ObjectMapper().findAndRegisterModules().writeValueAsString(config), "text/html").getBody())
                .contains("Saved");
    }

    @Test
    void reportControllerReturnsAttachments() throws IOException {
        ReportService reportService = Mockito.mock(ReportService.class);
        when(reportService.buildReport()).thenReturn(new byte[]{1, 2, 3});
        when(reportService.buildReport(LocalDate.of(2026, 3, 22))).thenReturn(new byte[]{4});
        when(reportService.buildDatedReportFileName(LocalDate.of(2026, 3, 22))).thenReturn("sequences-22-03-2026.xlsx");
        ReportController controller = new ReportController(reportService);

        assertThat(controller.downloadReport().getHeaders().getFirst("Content-Disposition")).contains("sequences.xlsx");
        assertThat(controller.downloadReportForDate(LocalDate.of(2026, 3, 22)).getHeaders().getFirst("Content-Disposition"))
                .contains("sequences-22-03-2026.xlsx");
    }

    @Test
    void sourceTriggerControllerMapsStatusesToHttpResponses() {
        SourcePullTriggerService service = Mockito.mock(SourcePullTriggerService.class);
        SourceTriggerController controller = new SourceTriggerController(service);

        when(service.triggerPull()).thenReturn(SourcePullTriggerService.TriggerResult.triggered(3));
        assertThat(controller.triggerSourcePull().getStatusCode()).isEqualTo(HttpStatus.OK);

        when(service.triggerPull()).thenReturn(SourcePullTriggerService.TriggerResult.cooldown(99));
        assertThat(controller.triggerSourcePull().getStatusCode().value()).isEqualTo(429);

        when(service.triggerPull()).thenReturn(SourcePullTriggerService.TriggerResult.running());
        assertThat(controller.triggerSourcePull().getStatusCode().value()).isEqualTo(409);
    }
}
