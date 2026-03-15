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

@Component
public class RuntimeConfig {
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    private final ObjectMapper objectMapper;
    private AppConfig appConfig;

    public RuntimeConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() throws IOException {
        Path configPath = Path.of(System.getProperty("user.dir"), "config.json");
        log.info("Loading runtime configuration from {}", configPath);
        if (!Files.exists(configPath)) {
            log.error("config.json was not found at {}", configPath);
            throw new IllegalStateException("config.json was not found near jar/application in " + configPath);
        }
        this.appConfig = objectMapper.readValue(Files.readString(configPath), AppConfig.class);
        log.info("Runtime configuration loaded: source schema={}, source table={}, notificationsEnabled={}",
                appConfig.getSourceDatabase().getSchema(),
                appConfig.getSourceTable().getTable(),
                appConfig.getNotifications() != null && appConfig.getNotifications().isEnabled());
    }

    public AppConfig get() {
        return appConfig;
    }
}
