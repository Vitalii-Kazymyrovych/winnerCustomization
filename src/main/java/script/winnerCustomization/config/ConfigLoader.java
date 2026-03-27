package script.winnerCustomization.config;
 
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
 
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
 
@Component
public class ConfigLoader {
 
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private AppConfig config;
 
    @PostConstruct
    public void init() {
        loadConfig();
    }
 
    private void loadConfig() {
        // Look for config.json next to the JAR file, or in the current working directory
        Path configPath = findConfigFile();
        log.info("Loading configuration from: {}", configPath.toAbsolutePath());
 
        ObjectMapper mapper = new ObjectMapper();
        try {
            config = mapper.readValue(configPath.toFile(), AppConfig.class);
            log.info("Configuration loaded successfully");
            log.info("Source DB: {}:{}/{}", config.getDatabase().getHost(),
                    config.getDatabase().getPort(), config.getDatabase().getSource().getDb());
            log.info("Target DB: {}:{}/{}", config.getDatabase().getHost(),
                    config.getDatabase().getPort(), config.getDatabase().getSequence().getDb());
            log.info("Source refresh interval: {} seconds", config.getSourceRefreshSeconds());
            log.info("Messaging enabled: {}", config.getMessaging().isEnabled());
            log.info("Workflow stages - Real: {}, Transitional: {}, SingleCamera: {}",
                    config.getWorkflow().getReal().size(),
                    config.getWorkflow().getTransitional().size(),
                    config.getWorkflow().getSingleCamera().size());
            log.info("Alert rules configured: {}", config.getAlerts().size());
        } catch (IOException e) {
            log.error("Failed to load config.json: {}", e.getMessage());
            throw new RuntimeException("Cannot start application without config.json", e);
        }
    }
 
    private Path findConfigFile() {
        // Try current working directory first
        Path cwd = Paths.get("config.json");
        if (cwd.toFile().exists()) {
            return cwd;
        }
 
        // Try next to the JAR file
        try {
            String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarDir = new File(jarPath).getParentFile();
            Path jarConfig = jarDir.toPath().resolve("config.json");
            if (jarConfig.toFile().exists()) {
                return jarConfig;
            }
        } catch (Exception e) {
            log.warn("Could not determine JAR location: {}", e.getMessage());
        }
 
        // Default to current directory (will fail with a clear error if not found)
        return cwd;
    }
 
    public AppConfig getConfig() {
        return config;
    }
}