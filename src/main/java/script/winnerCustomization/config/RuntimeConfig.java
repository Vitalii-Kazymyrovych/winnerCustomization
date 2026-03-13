package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class RuntimeConfig {
    private final ObjectMapper objectMapper;
    private AppConfig appConfig;

    public RuntimeConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() throws IOException {
        Path configPath = Path.of(System.getProperty("user.dir"), "config.json");
        if (!Files.exists(configPath)) {
            throw new IllegalStateException("config.json was not found near jar/application in " + configPath);
        }
        this.appConfig = objectMapper.readValue(Files.readString(configPath), AppConfig.class);
    }

    public AppConfig get() {
        return appConfig;
    }
}
