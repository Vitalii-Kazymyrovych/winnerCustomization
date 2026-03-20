package script.winnerCustomization.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConfigControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private RuntimeConfig runtimeConfig;

    @InjectMocks
    private ConfigController configController;

    @Test
    void shouldReturnCurrentConfigAsJson() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(runtimeConfig, objectMapper)).build();
        when(runtimeConfig.get()).thenReturn(sampleConfig());

        mockMvc.perform(get("/config").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTable.table").value("alpr_detections"))
                .andExpect(jsonPath("$.workflow.stages[0].name").value("drive_in"));
    }

    @Test
    void shouldRenderPrimitiveHtmlEditor() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(runtimeConfig, objectMapper)).build();
        when(runtimeConfig.get()).thenReturn(sampleConfig());

        mockMvc.perform(get("/config").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<textarea name=\"json\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("drive_in")));
    }

    @Test
    void shouldSaveJsonConfig() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(runtimeConfig, objectMapper)).build();
        AppConfig config = sampleConfig();
        when(runtimeConfig.save(any(AppConfig.class))).thenReturn(config);

        mockMvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflow.stages[0].labelTemplate").value("Drive In"));

        verify(runtimeConfig).save(any(AppConfig.class));
    }

    @Test
    void shouldSaveFormAndReturnHtml() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(runtimeConfig, objectMapper)).build();
        AppConfig config = sampleConfig();
        when(runtimeConfig.save(any(AppConfig.class))).thenReturn(config);
        when(runtimeConfig.get()).thenReturn(config);

        mockMvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("json", objectMapper.writeValueAsString(config))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save configuration")));
    }

    private AppConfig sampleConfig() {
        AppConfig config = new AppConfig();
        AppConfig.DatabaseConfig sourceDatabase = new AppConfig.DatabaseConfig();
        sourceDatabase.setSchema("videoanalytics");
        config.setSourceDatabase(sourceDatabase);
        AppConfig.SourceTableConfig sourceTable = new AppConfig.SourceTableConfig();
        sourceTable.setTable("alpr_detections");
        config.setSourceTable(sourceTable);
        AppConfig.WorkflowConfig workflow = new AppConfig.WorkflowConfig();
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName("drive_in");
        stage.setLabelTemplate("Drive In");
        AppConfig.TriggerConfig trigger = new AppConfig.TriggerConfig();
        trigger.setCameraId(1001);
        trigger.setEventKey("DRIVE_IN_IN");
        stage.setStartTriggers(java.util.List.of(trigger));
        workflow.setStages(java.util.List.of(stage));
        config.setWorkflow(workflow);
        return config;
    }
}
