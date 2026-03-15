package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.AppConfig;

@Service
public class DatabaseBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrapService.class);

    public void ensureDatabaseExists(AppConfig.RootDatabaseConfig rootConfig, AppConfig.DatabaseConfig targetDatabase) {
        if (rootConfig == null) {
            throw new IllegalStateException("config.json rootDatabase section is required to auto-create sequence database");
        }
        if (targetDatabase == null || targetDatabase.getDb() == null || targetDatabase.getDb().isBlank()) {
            throw new IllegalStateException("config.json sequenceDatabase.db must be provided");
        }
        if (targetDatabase.getUser() == null || targetDatabase.getUser().isBlank()) {
            throw new IllegalStateException("config.json sequenceDatabase.user must be provided");
        }
        if (targetDatabase.getPassword() == null || targetDatabase.getPassword().isBlank()) {
            throw new IllegalStateException("config.json sequenceDatabase.password must be provided");
        }

        String dbName = targetDatabase.getDb();
        log.info("Checking whether sequence database '{}' exists via root connection {}:{}",
                dbName, rootConfig.getHost(), rootConfig.getPort());

        JdbcTemplate rootJdbc = createRootJdbcTemplate(rootConfig);
        try {
            ensureRoleExists(rootJdbc, targetDatabase.getUser(), targetDatabase.getPassword());

            Boolean exists = rootJdbc.queryForObject(
                    "select exists(select 1 from pg_database where datname = ?)",
                    Boolean.class,
                    dbName
            );
            if (!Boolean.TRUE.equals(exists)) {
                String createSql = "create database " + quoteIdentifier(dbName)
                        + " owner " + quoteIdentifier(targetDatabase.getUser());
                log.info("Sequence database '{}' does not exist. Creating it now.", dbName);
                rootJdbc.execute(createSql);
                log.info("Sequence database '{}' created", dbName);
            } else {
                log.info("Sequence database '{}' already exists", dbName);
            }

            rootJdbc.execute("grant connect, temporary on database " + quoteIdentifier(dbName)
                    + " to " + quoteIdentifier(targetDatabase.getUser()));
            ensureSchemaPermissions(rootConfig, targetDatabase);
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "Failed to ensure sequence database '%s' exists using root account at %s:%d. " +
                            "Check rootDatabase credentials in config.json."
                            .formatted(dbName, rootConfig.getHost(), rootConfig.getPort()),
                    ex
            );
        }
    }

    JdbcTemplate createRootJdbcTemplate(AppConfig.RootDatabaseConfig rootConfig) {
        return createJdbcTemplate(rootConfig.getHost(), rootConfig.getPort(), rootConfig.getMaintenanceDb(),
                rootConfig.getUser(), rootConfig.getPassword());
    }

    JdbcTemplate createJdbcTemplate(String host, int port, String db, String user, String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
        ds.setUsername(user);
        ds.setPassword(password);
        return new JdbcTemplate(ds);
    }

    private void ensureRoleExists(JdbcTemplate rootJdbc, String username, String password) {
        Boolean roleExists = rootJdbc.queryForObject(
                "select exists(select 1 from pg_roles where rolname = ?)",
                Boolean.class,
                username
        );

        String passwordLiteral = quoteLiteral(password);
        String quotedUser = quoteIdentifier(username);

        if (!Boolean.TRUE.equals(roleExists)) {
            log.info("Sequence database role '{}' does not exist. Creating it now.", username);
            rootJdbc.execute("create role " + quotedUser + " login password " + passwordLiteral);
            log.info("Sequence database role '{}' created", username);
            return;
        }

        rootJdbc.execute("alter role " + quotedUser + " with login password " + passwordLiteral);
        log.info("Sequence database role '{}' password ensured", username);
    }

    private void ensureSchemaPermissions(AppConfig.RootDatabaseConfig rootConfig, AppConfig.DatabaseConfig targetDatabase) {
        JdbcTemplate targetJdbc = createJdbcTemplate(
                rootConfig.getHost(),
                rootConfig.getPort(),
                targetDatabase.getDb(),
                rootConfig.getUser(),
                rootConfig.getPassword()
        );

        String quotedUser = quoteIdentifier(targetDatabase.getUser());
        targetJdbc.execute("grant usage, create on schema public to " + quotedUser);
        targetJdbc.execute("alter schema public owner to " + quotedUser);
    }

    private String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private String quoteLiteral(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }
}
