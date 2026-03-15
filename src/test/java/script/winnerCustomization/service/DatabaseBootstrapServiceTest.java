package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import script.winnerCustomization.model.AppConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseBootstrapServiceTest {

    @Test
    void shouldCreateDatabaseWhenMissing() {
        JdbcTemplate rootJdbc = mock(JdbcTemplate.class);
        when(rootJdbc.queryForObject(any(String.class), eq(Boolean.class), eq("customization"))).thenReturn(false);

        DatabaseBootstrapService service = new TestableDatabaseBootstrapService(rootJdbc);
        service.ensureDatabaseExists(rootConfig(), sequenceConfig("customization"));

        verify(rootJdbc).execute("create database \"customization\"");
    }

    @Test
    void shouldSkipCreateWhenDatabaseAlreadyExists() {
        JdbcTemplate rootJdbc = mock(JdbcTemplate.class);
        when(rootJdbc.queryForObject(any(String.class), eq(Boolean.class), eq("customization"))).thenReturn(true);

        DatabaseBootstrapService service = new TestableDatabaseBootstrapService(rootJdbc);
        service.ensureDatabaseExists(rootConfig(), sequenceConfig("customization"));

        verify(rootJdbc, never()).execute(any(String.class));
    }

    private AppConfig.RootDatabaseConfig rootConfig() {
        AppConfig.RootDatabaseConfig config = new AppConfig.RootDatabaseConfig();
        config.setHost("localhost");
        config.setPort(5432);
        config.setUser("postgres");
        config.setPassword("postgres");
        return config;
    }

    private AppConfig.DatabaseConfig sequenceConfig(String dbName) {
        AppConfig.DatabaseConfig config = new AppConfig.DatabaseConfig();
        config.setDb(dbName);
        return config;
    }

    private static class TestableDatabaseBootstrapService extends DatabaseBootstrapService {
        private final JdbcTemplate rootJdbc;

        private TestableDatabaseBootstrapService(JdbcTemplate rootJdbc) {
            this.rootJdbc = rootJdbc;
        }

        @Override
        JdbcTemplate createRootJdbcTemplate(AppConfig.RootDatabaseConfig rootConfig) {
            return rootJdbc;
        }
    }
}
