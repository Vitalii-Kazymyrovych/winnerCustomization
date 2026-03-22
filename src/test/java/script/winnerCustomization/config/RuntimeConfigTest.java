package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.service.TestConfigFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigTest {
    private final RuntimeConfig runtimeConfig = new RuntimeConfig(new ObjectMapper().findAndRegisterModules());

    @Test
    void rejectsDuplicateStageNames() {
        var config = TestConfigFactory.config();
        config.getSingleCameraStages().getFirst().setName("drive_in");

        assertThatThrownBy(() -> runtimeConfig.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stage names must be unique");
    }

    @Test
    void rejectsEqualDirectionRangeBounds() {
        var config = TestConfigFactory.config();
        config.getRealStages().getFirst().getInTriggers().getFirst().getDirectionRange().setTo(0);

        assertThatThrownBy(() -> runtimeConfig.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from/to must differ");
    }
}
