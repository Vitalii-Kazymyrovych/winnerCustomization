package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    @Bean
    public AppConfig appConfig() throws IOException {
        Path configPath = resolveConfigPath();
        log.info("Loading config from {}", configPath.toAbsolutePath());
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        return mapper.readValue(Files.readString(configPath), AppConfig.class);
    }

    private Path resolveConfigPath() {
        Path current = Path.of("config.json");
        if (Files.exists(current)) {
            return current;
        }
        String jarDir = System.getProperty("user.dir");
        Path nextToJar = Path.of(jarDir, "config.json");
        if (Files.exists(nextToJar)) {
            return nextToJar;
        }
        throw new IllegalStateException("config.json is required next to the jar (or current working directory)");
    }
}
