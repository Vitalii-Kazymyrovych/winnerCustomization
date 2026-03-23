package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RuntimeConfig {
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    private final ObjectMapper objectMapper;
    private final AtomicReference<AppConfig> appConfig = new AtomicReference<>();
    private final Path configPath;

    public RuntimeConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.configPath = Path.of(System.getProperty("user.dir"), "config.json");
    }

    @PostConstruct
    public void load() throws IOException {
        if (!Files.exists(configPath)) {
            throw new IllegalStateException("config.json was not found near the application: " + configPath);
        }
        reload();
    }

    public synchronized AppConfig reload() throws IOException {
        AppConfig loaded = objectMapper.readValue(Files.readString(configPath), AppConfig.class);
        validate(loaded);
        appConfig.set(loaded);
        log.info("Runtime configuration loaded from {}", configPath);
        return loaded;
    }

    public synchronized AppConfig save(AppConfig config) throws IOException {
        validate(config);
        Files.writeString(configPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        appConfig.set(config);
        return config;
    }

    public AppConfig get() {
        return appConfig.get();
    }

    public Path getConfigPath() {
        return configPath;
    }

    public void validate(AppConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Config body is required");
        }
        require(config.getSourceDatabase() != null && !isBlank(config.getSourceDatabase().getSchema()), "sourceDatabase.schema is required");
        require(config.getSourceTable() != null && !isBlank(config.getSourceTable().getTable()), "sourceTable.table is required");
        require(config.getSequenceCloseTimeoutMinutes() != null && config.getSequenceCloseTimeoutMinutes() > 0, "sequenceCloseTimeoutMinutes must be positive");
        require(config.getReports() != null && !isBlank(config.getReports().getOutputDirectory()), "reports.outputDirectory is required");
        require(config.getSourceRefresh() != null, "sourceRefresh is required");
        require(config.getSourceRefresh().getIntervalSeconds() != null && config.getSourceRefresh().getIntervalSeconds() > 0,
                "sourceRefresh.intervalSeconds must be positive");

        Set<String> stageNames = new HashSet<>();
        validateRealStages(config, stageNames);
        validateSingleStages(config, stageNames);
        validateTransitionalStages(config, stageNames);
        validateNotifications(config);
    }

    private void validateRealStages(AppConfig config, Set<String> stageNames) {
        for (AppConfig.RealStageConfig stage : safe(config.getRealStages())) {
            validateStageIdentity(stage.getName(), stage.getLabel(), stageNames, "realStages");
            require(!safe(stage.getInTriggers()).isEmpty(), "realStages." + stage.getName() + ".inTriggers must not be empty");
            require(!safe(stage.getOutTriggers()).isEmpty(), "realStages." + stage.getName() + ".outTriggers must not be empty");
            stage.getInTriggers().forEach(trigger -> validateTrigger(trigger, "realStages." + stage.getName() + ".inTriggers"));
            stage.getOutTriggers().forEach(trigger -> validateTrigger(trigger, "realStages." + stage.getName() + ".outTriggers"));
        }
    }

    private void validateSingleStages(AppConfig config, Set<String> stageNames) {
        for (AppConfig.SingleCameraStageConfig stage : safe(config.getSingleCameraStages())) {
            validateStageIdentity(stage.getName(), stage.getLabel(), stageNames, "singleCameraStages");
            require(stage.getCameraId() != null, "singleCameraStages." + stage.getName() + ".cameraId is required");
            require(stage.getTimeoutSeconds() != null && stage.getTimeoutSeconds() > 0,
                    "singleCameraStages." + stage.getName() + ".timeoutSeconds must be positive");
        }
    }

    private void validateTransitionalStages(AppConfig config, Set<String> stageNames) {
        for (AppConfig.TransitionalStageConfig stage : safe(config.getTransitionalStages())) {
            validateStageIdentity(stage.getName(), stage.getLabel(), stageNames, "transitionalStages");
            require(!safe(stage.getTriggerCameras()).isEmpty() || !safe(stage.getAllowedAfter()).isEmpty(),
                    "transitionalStages." + stage.getName() + ".must define triggerCameras or allowedAfter");
            require(stage.getCandidateTimeoutSeconds() != null && stage.getCandidateTimeoutSeconds() > 0,
                    "transitionalStages." + stage.getName() + ".candidateTimeoutSeconds must be positive");
            require(stage.getSequenceCloseTimeoutOverrideSeconds() == null || stage.getSequenceCloseTimeoutOverrideSeconds() >= 0,
                    "transitionalStages." + stage.getName() + ".sequenceCloseTimeoutOverrideSeconds must be zero or positive");
            for (String allowed : safe(stage.getAllowedAfter())) {
                require(stageNames.contains(allowed),
                        "transitionalStages." + stage.getName() + ".allowedAfter references unknown stage '" + allowed + "'");
            }
        }
    }

    private void validateNotifications(AppConfig config) {
        for (AppConfig.NotificationRule rule : safe(config.getNotifications())) {
            require(rule.getCameraId() != null, "notifications[].cameraId is required");
            require(rule.getDelaySeconds() != null && rule.getDelaySeconds() > 0, "notifications[].delaySeconds must be positive");
            require(!isBlank(rule.getMessage()), "notifications[].message is required");
            validateDirectionRange(rule.getDirectionRange(), "notifications[].directionRange");
        }
    }

    private void validateStageIdentity(String name, String label, Set<String> stageNames, String path) {
        require(!isBlank(name), path + "[].name is required");
        require(!isBlank(label), path + "." + name + ".label is required");
        require(stageNames.add(name), "Stage names must be unique: " + name);
    }

    private void validateTrigger(AppConfig.CameraTrigger trigger, String path) {
        require(trigger != null && trigger.getCameraId() != null, path + "[].cameraId is required");
        validateDirectionRange(trigger.getDirectionRange(), path + "[].directionRange");
    }

    private void validateDirectionRange(AppConfig.DirectionRange range, String path) {
        if (range == null) {
            return;
        }
        require(range.getFrom() != null && range.getTo() != null, path + " must contain both from and to");
        require(!Objects.equals(range.getFrom(), range.getTo()), path + " from/to must differ");
    }

    private <T> List<T> safe(List<T> items) {
        return items == null ? List.of() : items;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
