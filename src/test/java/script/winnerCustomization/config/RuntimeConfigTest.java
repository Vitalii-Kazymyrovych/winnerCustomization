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
    private final RuntimeConfig runtimeConfig = new RuntimeConfig(new ObjectMapper().findAndRegisterModules());

    @Test
    void allowsTransitionalAfterSingleCameraViaAllowedAfterOnly() {
        AppConfig config = TestConfigFactory.standardConfig();
        config.getTransitionalStages().getFirst().setTriggerCameras(List.of());
        config.getTransitionalStages().getFirst().setAllowedAfter(List.of("post_1"));

        runtimeConfig.validate(config);
    }

    @Test
    void rejectsUnknownAllowedAfterAndDuplicateStageNames() {
        AppConfig config = TestConfigFactory.standardConfig();
        config.getTransitionalStages().getFirst().setAllowedAfter(List.of("unknown"));

        assertThatThrownBy(() -> runtimeConfig.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedAfter references unknown stage");

        AppConfig duplicateNames = TestConfigFactory.standardConfig();
        duplicateNames.getSingleCameraStages().getFirst().setName("drive_in");
        assertThatThrownBy(() -> runtimeConfig.validate(duplicateNames))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stage names must be unique");
    }


    @Test
    void rejectsMissingReportOutputDirectory() {
        AppConfig config = TestConfigFactory.standardConfig();
        config.getReports().setOutputDirectory("   ");

        assertThatThrownBy(() -> runtimeConfig.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reports.outputDirectory is required");
    }

    @Test
    void rejectsNonPositiveSourceRefreshInterval() {
        AppConfig config = TestConfigFactory.standardConfig();
        config.getSourceRefresh().setIntervalSeconds(0);

        assertThatThrownBy(() -> runtimeConfig.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceRefresh.intervalSeconds must be positive");
    }

    @Test
    void loadReloadAndSaveUseConfigNearWorkingDirectory() throws Exception {
        Path workdir = Files.createTempDirectory("runtime-config");
        Path configPath = workdir.resolve("config.json");
        Files.writeString(configPath, new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
                .writeValueAsString(TestConfigFactory.standardConfig()));

        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", workdir.toString());
        try {
            RuntimeConfig fileConfig = new RuntimeConfig(new ObjectMapper().findAndRegisterModules());
            fileConfig.load();
            assertThat(fileConfig.get().getSourceTable().getTable()).isEqualTo("alpr_detections");
            fileConfig.get().getSourceTable().setTable("changed_table");
            fileConfig.save(fileConfig.get());
            fileConfig.reload();
            assertThat(fileConfig.get().getSourceTable().getTable()).isEqualTo("changed_table");
            assertThat(fileConfig.getConfigPath()).isEqualTo(configPath);
        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }
}
