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
        JdbcTemplate targetJdbc = mock(JdbcTemplate.class);
        when(rootJdbc.queryForObject(any(String.class), eq(Boolean.class), eq("sequence_user"))).thenReturn(false);
        when(rootJdbc.queryForObject(any(String.class), eq(Boolean.class), eq("customization"))).thenReturn(false);

        DatabaseBootstrapService service = new TestableDatabaseBootstrapService(rootJdbc, targetJdbc);
        service.ensureDatabaseExists(rootConfig(), sequenceConfig("customization"));

        verify(rootJdbc).execute("create role \"sequence_user\" login password 'sequence_password'");
        verify(rootJdbc).execute("create database \"customization\" owner \"sequence_user\"");
        verify(rootJdbc).execute("grant connect, temporary on database \"customization\" to \"sequence_user\"");
        verify(targetJdbc).execute("grant usage, create on schema public to \"sequence_user\"");
        verify(targetJdbc).execute("alter schema public owner to \"sequence_user\"");
    }

    @Test
    void shouldSkipCreateWhenDatabaseAlreadyExists() {
        JdbcTemplate rootJdbc = mock(JdbcTemplate.class);
        JdbcTemplate targetJdbc = mock(JdbcTemplate.class);
        when(rootJdbc.queryForObject(any(String.class), eq(Boolean.class), eq("sequence_user"))).thenReturn(true);
        when(rootJdbc.queryForObject(any(String.class), eq(Boolean.class), eq("customization"))).thenReturn(true);

        DatabaseBootstrapService service = new TestableDatabaseBootstrapService(rootJdbc, targetJdbc);
        service.ensureDatabaseExists(rootConfig(), sequenceConfig("customization"));

        verify(rootJdbc, never()).execute("create database \"customization\" owner \"sequence_user\"");
        verify(rootJdbc).execute("alter role \"sequence_user\" with login password 'sequence_password'");
        verify(rootJdbc).execute("grant connect, temporary on database \"customization\" to \"sequence_user\"");
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
        config.setUser("sequence_user");
        config.setPassword("sequence_password");
        return config;
    }

    private static class TestableDatabaseBootstrapService extends DatabaseBootstrapService {
        private final JdbcTemplate rootJdbc;
        private final JdbcTemplate targetJdbc;

        private TestableDatabaseBootstrapService(JdbcTemplate rootJdbc, JdbcTemplate targetJdbc) {
            this.rootJdbc = rootJdbc;
            this.targetJdbc = targetJdbc;
        }

        @Override
        JdbcTemplate createRootJdbcTemplate(AppConfig.RootDatabaseConfig rootConfig) {
            return rootJdbc;
        }

        @Override
        JdbcTemplate createJdbcTemplate(String host, int port, String db, String user, String password) {
            if ("postgres".equals(db)) {
                return rootJdbc;
            }
            return targetJdbc;
        }
    }
}
