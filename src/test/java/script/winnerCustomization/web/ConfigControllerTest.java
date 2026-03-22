package script.winnerCustomization.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.service.TestConfigFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigControllerTest {
    @Test
    void returnsHelpPageWithNewSections() {
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.get()).thenReturn(TestConfigFactory.config());
        ConfigController controller = new ConfigController(runtimeConfig, new ObjectMapper().findAndRegisterModules());

        String body = controller.getConfigHelpHtml().getBody();

        assertThat(body).contains("realStages");
        assertThat(body).contains("transitionalStages");
        assertThat(body).contains("singleCameraStages");
    }
}
