package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();


    @Test
    void allowsTransitionalStageWithoutTriggerCamerasWhenAllowedAfterIsConfigured() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        AppConfig.TransitionalStageConfig backyard = config.getTransitionalStages().getFirst();
        backyard.setTriggerCameras(List.of());
        backyard.setAllowedAfter(List.of("service"));

        runtimeConfig().validate(config);
    }

    @Test
    void rejectsDuplicateStageNames() {
        var config = script.winnerCustomization.service.TestConfigFactory.config();
        config.getSingleCameraStages().getFirst().setName("drive_in");

        assertThatThrownBy(() -> runtimeConfig().validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stage names must be unique");
    }

    @Test
    void rejectsEqualDirectionRangeBounds() {
        var config = script.winnerCustomization.service.TestConfigFactory.config();
        config.getRealStages().getFirst().getInTriggers().getFirst().getDirectionRange().setTo(0);

        assertThatThrownBy(() -> runtimeConfig().validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from/to must differ");
    }

    @Test
    void loadReloadAndSaveWorkAgainstConfigFile() throws Exception {
        Path workingDir = Files.createTempDirectory("runtime-config-test");
        Path configPath = workingDir.resolve("config.json");
        AppConfig config = TestFixtures.configWithReportDirectory("");
        Files.writeString(configPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config));

        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", workingDir.toString());
        try {
            RuntimeConfig runtimeConfig = runtimeConfig();
            runtimeConfig.load();
            assertThat(runtimeConfig.get().getSourceTable().getTable()).isEqualTo("alpr_detections");

            runtimeConfig.get().getSourceTable().setTable("changed_table");
            runtimeConfig.save(runtimeConfig.get());
            runtimeConfig.reload();

            assertThat(runtimeConfig.get().getSourceTable().getTable()).isEqualTo("changed_table");
            assertThat(runtimeConfig.getConfigPath()).isEqualTo(configPath);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void loadFailsWhenConfigMissing() throws Exception {
        Path workingDir = Files.createTempDirectory("runtime-config-missing");
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", workingDir.toString());
        try {
            RuntimeConfig runtimeConfig = runtimeConfig();
            assertThatThrownBy(runtimeConfig::load)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("config.json was not found");
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void validateRejectsInvalidRequiredFieldsAndUnknownAllowedAfter() {
        RuntimeConfig runtimeConfig = runtimeConfig();

        assertThatThrownBy(() -> runtimeConfig.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Config body is required");

        AppConfig unknownAllowedAfter = TestFixtures.configWithReportDirectory("");
        unknownAllowedAfter.getTransitionalStages().getFirst().setAllowedAfter(new java.util.ArrayList<>(unknownAllowedAfter.getTransitionalStages().getFirst().getAllowedAfter()));
        unknownAllowedAfter.getTransitionalStages().getFirst().getAllowedAfter().add("unknown");
        assertThatThrownBy(() -> runtimeConfig.validate(unknownAllowedAfter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedAfter references unknown stage");

        AppConfig blankNotificationMessage = TestFixtures.configWithReportDirectory("");
        blankNotificationMessage.getNotifications().getFirst().setMessage(" ");
        assertThatThrownBy(() -> runtimeConfig.validate(blankNotificationMessage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notifications[].message is required");
    }

    @Test
    void validateRejectsInvalidTimeoutsAndTriggerDefinitions() {
        RuntimeConfig runtimeConfig = runtimeConfig();
        AppConfig zeroSequenceTimeout = TestFixtures.configWithReportDirectory("");
        zeroSequenceTimeout.setSequenceCloseTimeoutMinutes(0);
        assertThatThrownBy(() -> runtimeConfig.validate(zeroSequenceTimeout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceCloseTimeoutMinutes must be positive");

        AppConfig negativeDuplicateSuppression = TestFixtures.configWithReportDirectory("");
        negativeDuplicateSuppression.setDuplicateSuppressionSeconds(-1);
        assertThatThrownBy(() -> runtimeConfig.validate(negativeDuplicateSuppression))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicateSuppressionSeconds must be zero or positive");

        AppConfig missingRealInTriggers = TestFixtures.configWithReportDirectory("");
        missingRealInTriggers.getRealStages().getFirst().setInTriggers(java.util.List.of());
        assertThatThrownBy(() -> runtimeConfig.validate(missingRealInTriggers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inTriggers must not be empty");

        AppConfig zeroSingleTimeout = TestFixtures.configWithReportDirectory("");
        zeroSingleTimeout.getSingleCameraStages().getFirst().setTimeoutSeconds(0);
        assertThatThrownBy(() -> runtimeConfig.validate(zeroSingleTimeout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds must be positive");

        AppConfig missingTriggerCameras = TestFixtures.configWithReportDirectory("");
        missingTriggerCameras.getTransitionalStages().getFirst().setTriggerCameras(java.util.List.of());
        missingTriggerCameras.getTransitionalStages().getFirst().setAllowedAfter(java.util.List.of());
        assertThatThrownBy(() -> runtimeConfig.validate(missingTriggerCameras))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must define triggerCameras or allowedAfter");
    }

    private RuntimeConfig runtimeConfig() {
        return new RuntimeConfig(objectMapper);
    }
}
