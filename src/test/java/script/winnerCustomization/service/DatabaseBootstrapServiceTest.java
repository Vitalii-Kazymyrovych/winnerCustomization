package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import script.winnerCustomization.model.AppConfig;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseBootstrapServiceTest {
    @Test
    void ensureDatabaseExistsValidatesRequiredFields() {
        DatabaseBootstrapService service = new DatabaseBootstrapService();
        AppConfig.DatabaseConfig target = new AppConfig.DatabaseConfig();

        assertThatThrownBy(() -> service.ensureDatabaseExists(null, target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rootDatabase section is required");

        AppConfig.RootDatabaseConfig root = new AppConfig.RootDatabaseConfig();
        target.setDb("db");
        assertThatThrownBy(() -> service.ensureDatabaseExists(root, target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequenceDatabase.user must be provided");
    }

    @Test
    void ensureDatabaseExistsCreatesRoleDatabaseAndGrantsPermissions() {
        RecordingBootstrapService service = new RecordingBootstrapService(false, false);
        AppConfig.RootDatabaseConfig root = rootConfig();
        AppConfig.DatabaseConfig target = targetConfig();

        service.ensureDatabaseExists(root, target);

        assertThat(service.rootStatements).anyMatch(sql -> sql.contains("create role \"app_user\" login password 'secret'"));
        assertThat(service.rootStatements).anyMatch(sql -> sql.contains("create database \"seq_db\" owner \"app_user\""));
        assertThat(service.rootStatements).anyMatch(sql -> sql.contains("grant connect, temporary on database \"seq_db\" to \"app_user\""));
        assertThat(service.targetStatements).containsExactly(
                "grant usage, create on schema public to \"app_user\"",
                "alter schema public owner to \"app_user\""
        );
    }

    @Test
    void ensureDatabaseExistsUpdatesExistingRoleAndWrapsDataAccessException() {
        RecordingBootstrapService service = new RecordingBootstrapService(true, true);

        assertThatThrownBy(() -> service.ensureDatabaseExists(rootConfig(), targetConfig()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to ensure sequence database '%s' exists");
    }

    @Test
    void createJdbcTemplateProducesPostgresDriverManagerTemplate() {
        DatabaseBootstrapService service = new DatabaseBootstrapService();

        JdbcTemplate jdbcTemplate = service.createJdbcTemplate("localhost", 5432, "db", "user", "pass");

        assertThat(jdbcTemplate.getDataSource()).isNotNull();
    }

    private AppConfig.RootDatabaseConfig rootConfig() {
        AppConfig.RootDatabaseConfig root = new AppConfig.RootDatabaseConfig();
        root.setHost("localhost");
        root.setPort(5432);
        root.setUser("postgres");
        root.setPassword("root");
        return root;
    }

    private AppConfig.DatabaseConfig targetConfig() {
        AppConfig.DatabaseConfig target = new AppConfig.DatabaseConfig();
        target.setHost("localhost");
        target.setPort(5432);
        target.setDb("seq_db");
        target.setSchema("public");
        target.setUser("app_user");
        target.setPassword("secret");
        return target;
    }

    private static final class RecordingBootstrapService extends DatabaseBootstrapService {
        private final JdbcTemplate rootJdbc = mock(JdbcTemplate.class);
        private final JdbcTemplate targetJdbc = mock(JdbcTemplate.class);
        private final List<String> rootStatements = new ArrayList<>();
        private final List<String> targetStatements = new ArrayList<>();
        private final boolean databaseExists;
        private final boolean throwOnExistsCheck;

        private RecordingBootstrapService(boolean databaseExists, boolean throwOnExistsCheck) {
            this.databaseExists = databaseExists;
            this.throwOnExistsCheck = throwOnExistsCheck;
            when(rootJdbc.queryForObject(eq("select exists(select 1 from pg_roles where rolname = ?)"), eq(Boolean.class), any()))
                    .thenReturn(databaseExists);
            if (throwOnExistsCheck) {
                when(rootJdbc.queryForObject(eq("select exists(select 1 from pg_database where datname = ?)"), eq(Boolean.class), any()))
                        .thenThrow(new DataAccessResourceFailureException("boom"));
            } else {
                when(rootJdbc.queryForObject(eq("select exists(select 1 from pg_database where datname = ?)"), eq(Boolean.class), any()))
                        .thenReturn(databaseExists);
            }
            when(rootJdbc.update(any(String.class), any(), any(), any())).thenReturn(1);
            when(targetJdbc.update(any(String.class), any(), any())).thenReturn(1);
            org.mockito.Mockito.doAnswer(invocation -> {
                rootStatements.add(invocation.getArgument(0));
                return null;
            }).when(rootJdbc).execute(any(String.class));
            org.mockito.Mockito.doAnswer(invocation -> {
                targetStatements.add(invocation.getArgument(0));
                return null;
            }).when(targetJdbc).execute(any(String.class));
        }

        @Override
        JdbcTemplate createRootJdbcTemplate(AppConfig.RootDatabaseConfig rootConfig) {
            return rootJdbc;
        }

        @Override
        JdbcTemplate createJdbcTemplate(String host, int port, String db, String user, String password) {
            return "seq_db".equals(db) ? targetJdbc : rootJdbc;
        }
    }
}
