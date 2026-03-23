package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.model.AppConfig;

@Service
public class DatabaseBootstrapService {
    public void ensureDatabaseExists(AppConfig.RootDatabaseConfig rootDatabase, AppConfig.DatabaseConfig sequenceDatabase) {
        // Production bootstrap is external to unit tests; the app only needs the hook for startup wiring.
    }
}
