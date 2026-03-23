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
import java.util.Set;
import java.util.Objects;
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
        if (config.getSourceDatabase() == null || isBlank(config.getSourceDatabase().getSchema())) {
            throw new IllegalArgumentException("sourceDatabase.schema is required");
        }
        if (config.getSourceTable() == null || isBlank(config.getSourceTable().getTable())) {
            throw new IllegalArgumentException("sourceTable.table is required");
        }
        if (config.getSequenceCloseTimeoutMinutes() == null || config.getSequenceCloseTimeoutMinutes() <= 0) {
            throw new IllegalArgumentException("sequenceCloseTimeoutMinutes must be positive");
        }
        if (config.getDuplicateSuppressionSeconds() == null || config.getDuplicateSuppressionSeconds() < 0) {
            throw new IllegalArgumentException("duplicateSuppressionSeconds must be zero or positive");
        }

        Set<String> stageNames = new HashSet<>();
        validateRealStages(config, stageNames);
        validateSingleStages(config, stageNames);
        validateTransitionalStages(config, stageNames);
        validateNotifications(config);
    }

    private void validateRealStages(AppConfig config, Set<String> stageNames) {
        for (AppConfig.RealStageConfig stage : safe(config.getRealStages())) {
            validateStageIdentity(stage.getName(), stage.getLabel(), stageNames, "realStages");
            if (safe(stage.getInTriggers()).isEmpty()) {
                throw new IllegalArgumentException("realStages." + stage.getName() + ".inTriggers must not be empty");
            }
            if (safe(stage.getOutTriggers()).isEmpty()) {
                throw new IllegalArgumentException("realStages." + stage.getName() + ".outTriggers must not be empty");
            }
            stage.getInTriggers().forEach(trigger -> validateTrigger(trigger, "realStages." + stage.getName() + ".inTriggers"));
            stage.getOutTriggers().forEach(trigger -> validateTrigger(trigger, "realStages." + stage.getName() + ".outTriggers"));
        }
    }

    private void validateSingleStages(AppConfig config, Set<String> stageNames) {
        for (AppConfig.SingleCameraStageConfig stage : safe(config.getSingleCameraStages())) {
            validateStageIdentity(stage.getName(), stage.getLabel(), stageNames, "singleCameraStages");
            if (stage.getCameraId() == null) {
                throw new IllegalArgumentException("singleCameraStages." + stage.getName() + ".cameraId is required");
            }
            if (stage.getTimeoutSeconds() == null || stage.getTimeoutSeconds() <= 0) {
                throw new IllegalArgumentException("singleCameraStages." + stage.getName() + ".timeoutSeconds must be positive");
            }
        }
    }

    private void validateTransitionalStages(AppConfig config, Set<String> stageNames) {
        for (AppConfig.TransitionalStageConfig stage : safe(config.getTransitionalStages())) {
            validateStageIdentity(stage.getName(), stage.getLabel(), stageNames, "transitionalStages");
            if (safe(stage.getTriggerCameras()).isEmpty() && safe(stage.getAllowedAfter()).isEmpty()) {
                throw new IllegalArgumentException("transitionalStages." + stage.getName() + ".must define triggerCameras or allowedAfter");
            }
            if (stage.getCandidateTimeoutSeconds() == null || stage.getCandidateTimeoutSeconds() <= 0) {
                throw new IllegalArgumentException("transitionalStages." + stage.getName() + ".candidateTimeoutSeconds must be positive");
            }
            if (stage.getSequenceCloseTimeoutOverrideSeconds() != null && stage.getSequenceCloseTimeoutOverrideSeconds() < 0) {
                throw new IllegalArgumentException("transitionalStages." + stage.getName() + ".sequenceCloseTimeoutOverrideSeconds must be zero or positive");
            }
            for (String allowed : safe(stage.getAllowedAfter())) {
                if (!stageNames.contains(allowed)) {
                    throw new IllegalArgumentException("transitionalStages." + stage.getName() + ".allowedAfter references unknown stage '" + allowed + "'");
                }
            }
        }
    }

    private void validateNotifications(AppConfig config) {
        for (AppConfig.NotificationRule rule : safe(config.getNotifications())) {
            if (rule.getCameraId() == null) {
                throw new IllegalArgumentException("notifications[].cameraId is required");
            }
            if (rule.getDelaySeconds() == null || rule.getDelaySeconds() <= 0) {
                throw new IllegalArgumentException("notifications[].delaySeconds must be positive");
            }
            if (isBlank(rule.getMessage())) {
                throw new IllegalArgumentException("notifications[].message is required");
            }
            validateDirectionRange(rule.getDirectionRange(), "notifications[].directionRange");
        }
    }

    private void validateStageIdentity(String name, String label, Set<String> stageNames, String path) {
        if (isBlank(name)) {
            throw new IllegalArgumentException(path + "[].name is required");
        }
        if (isBlank(label)) {
            throw new IllegalArgumentException(path + "." + name + ".label is required");
        }
        if (!stageNames.add(name)) {
            throw new IllegalArgumentException("Stage names must be unique: " + name);
        }
    }

    private void validateTrigger(AppConfig.CameraTrigger trigger, String path) {
        if (trigger == null || trigger.getCameraId() == null) {
            throw new IllegalArgumentException(path + "[].cameraId is required");
        }
        validateDirectionRange(trigger.getDirectionRange(), path + "[].directionRange");
    }

    private void validateDirectionRange(AppConfig.DirectionRange range, String path) {
        if (range == null) {
            return;
        }
        if (range.getFrom() == null || range.getTo() == null) {
            throw new IllegalArgumentException(path + " must contain both from and to");
        }
        if (Objects.equals(range.getFrom(), range.getTo())) {
            throw new IllegalArgumentException(path + " from/to must differ");
        }
    }

    private <T> java.util.List<T> safe(java.util.List<T> items) {
        return items == null ? java.util.List.of() : items;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
