package script.winnerCustomization.bootstrap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.AppConfig;

@Service
public class TargetDatabaseBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(TargetDatabaseBootstrapService.class);
    private final AppConfig appConfig;

    public TargetDatabaseBootstrapService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void bootstrap() {
        log.info("Target bootstrap is configured for host={} port={} db={} schema={}",
            appConfig.getDatabase().getHost(),
            appConfig.getDatabase().getPort(),
            appConfig.getDatabase().getSequence().getDb(),
            appConfig.getDatabase().getSequence().getSchema());
        log.info("This implementation uses an in-memory target repository and can be switched to JDBC repositories.");
    }
}
