package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.service.WorkflowDefaultsFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RuntimeConfig {
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    private final ObjectMapper objectMapper;
    private final WorkflowDefaultsFactory workflowDefaultsFactory;
    private final AtomicReference<AppConfig> appConfig = new AtomicReference<>();
    private final Path configPath;

    public RuntimeConfig(ObjectMapper objectMapper,
                         WorkflowDefaultsFactory workflowDefaultsFactory) {
        this.objectMapper = objectMapper;
        this.workflowDefaultsFactory = workflowDefaultsFactory;
        this.configPath = Path.of(System.getProperty("user.dir"), "config.json");
    }

    @PostConstruct
    public void load() throws IOException {
        log.info("Loading runtime configuration from {}", configPath);
        if (!Files.exists(configPath)) {
            log.error("config.json was not found at {}", configPath);
            throw new IllegalStateException("config.json was not found near jar/application in " + configPath);
        }
        reload();
    }

    public synchronized AppConfig reload() throws IOException {
        AppConfig loaded = objectMapper.readValue(Files.readString(configPath), AppConfig.class);
        validate(loaded);
        AppConfig enriched = workflowDefaultsFactory.enrich(loaded);
        appConfig.set(enriched);
        log.info("Runtime configuration loaded: source schema={}, source table={}, notificationsEnabled={}, workflowStages={}",
                enriched.getSourceDatabase().getSchema(),
                enriched.getSourceTable().getTable(),
                enriched.getNotifications() != null && enriched.getNotifications().isEnabled(),
                enriched.getWorkflow() == null || enriched.getWorkflow().getStages() == null ? 0 : enriched.getWorkflow().getStages().size());
        return enriched;
    }

    public synchronized AppConfig save(AppConfig config) throws IOException {
        validate(config);
        AppConfig enriched = workflowDefaultsFactory.enrich(config);
        Files.writeString(configPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(enriched));
        appConfig.set(enriched);
        return enriched;
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
        if (config.getWorkflow() == null || config.getWorkflow().getStages() == null || config.getWorkflow().getStages().isEmpty()) {
            return;
        }
        Set<String> stageNames = new HashSet<>();
        for (AppConfig.StageConfig stage : config.getWorkflow().getStages()) {
            if (stage == null || isBlank(stage.getName())) {
                throw new IllegalArgumentException("workflow.stages[].name is required");
            }
            if (!stageNames.add(stage.getName())) {
                throw new IllegalArgumentException("workflow stage names must be unique: " + stage.getName());
            }
            if (isBlank(stage.getLabelTemplate())) {
                throw new IllegalArgumentException("workflow stage labelTemplate is required for " + stage.getName());
            }
            validateTriggers(stage.getStartTriggers(), stage.getName(), "startTriggers");
            validateEnum(stage.getStartMode(), Set.of("immediate", "candidate"), "startMode", stage.getName());
            validateEnum(stage.getFinishMode(), Set.of("immediate", "sticky"), "finishMode", stage.getName());
            validateEnum(stage.getUnexpectedNextStagePolicy(), Set.of("close_current_and_start_next", "insert_intermediate_and_start_next", "ignore", "start_partial_next"), "unexpectedNextStagePolicy", stage.getName());
            validateEnum(stage.getStartDuplicatePolicy(), Set.of("ignore", "restart", "refresh_candidate"), "startDuplicatePolicy", stage.getName());
            validateEnum(stage.getFinishDuplicatePolicy(), Set.of("ignore", "update_sticky"), "finishDuplicatePolicy", stage.getName());
            validateTriggers(stage.getFinishTriggers(), stage.getName(), "finishTriggers");
            if (stage.getCandidateTimeoutMinutes() != null && stage.getCandidateTimeoutMinutes() <= 0) {
                throw new IllegalArgumentException("candidateTimeoutMinutes must be positive for " + stage.getName());
            }
            if (stage.getStickyCloseTimeoutMinutes() != null && stage.getStickyCloseTimeoutMinutes() <= 0) {
                throw new IllegalArgumentException("stickyCloseTimeoutMinutes must be positive for " + stage.getName());
            }
            if (stage.getSequenceCloseTimeoutMinutes() != null && stage.getSequenceCloseTimeoutMinutes() <= 0) {
                throw new IllegalArgumentException("sequenceCloseTimeoutMinutes must be positive for " + stage.getName());
            }
        }
        for (AppConfig.StageConfig stage : config.getWorkflow().getStages()) {
            validateReferences(stage.getAllowedNextStages(), stageNames, stage.getName(), "allowedNextStages");
            validateReference(stage.getTimeoutTransitionToStage(), stageNames, stage.getName(), "timeoutTransitionToStage");
            validateReference(stage.getIntermediateStageOnTransition(), stageNames, stage.getName(), "intermediateStageOnTransition");
        }
    }

    private void validateTriggers(java.util.List<AppConfig.TriggerConfig> triggers, String stageName, String fieldName) {
        if (triggers == null) {
            return;
        }
        for (AppConfig.TriggerConfig trigger : triggers) {
            if (trigger.getCameraId() == null) {
                throw new IllegalArgumentException("cameraId is required for " + stageName + "." + fieldName);
            }
            if (trigger.getDirectionRange() != null
                    && trigger.getDirectionRange().getFrom() != null
                    && trigger.getDirectionRange().getTo() != null
                    && trigger.getDirectionRange().getFrom().equals(trigger.getDirectionRange().getTo())) {
                throw new IllegalArgumentException("direction range from/to must not be equal for " + stageName + "." + fieldName);
            }
            if (isBlank(trigger.getEventKey())) {
                throw new IllegalArgumentException("eventKey is required for " + stageName + "." + fieldName);
            }
        }
    }

    private void validateReferences(java.util.List<String> references, Set<String> stageNames, String stageName, String fieldName) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            validateReference(reference, stageNames, stageName, fieldName);
        }
    }

    private void validateReference(String reference, Set<String> stageNames, String stageName, String fieldName) {
        if (isBlank(reference)) {
            return;
        }
        if (!stageNames.contains(reference)) {
            throw new IllegalArgumentException(fieldName + " references missing stage '" + reference + "' from " + stageName);
        }
    }

    private void validateEnum(String value, Set<String> allowed, String fieldName, String stageName) {
        if (isBlank(value)) {
            return;
        }
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(fieldName + " has unsupported value '" + value + "' for " + stageName);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
