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

        String dbName = targetDatabase.getDb();
        log.info("Checking whether sequence database '{}' exists via root connection {}:{}",
                dbName, rootConfig.getHost(), rootConfig.getPort());

        JdbcTemplate rootJdbc = createRootJdbcTemplate(rootConfig);
        try {
            Integer exists = rootJdbc.queryForObject(
                    "select 1 from pg_database where datname = ?",
                    Integer.class,
                    dbName
            );
            if (exists == null) {
                String createSql = "create database " + quoteIdentifier(dbName);
                log.info("Sequence database '{}' does not exist. Creating it now.", dbName);
                rootJdbc.execute(createSql);
                log.info("Sequence database '{}' created", dbName);
            } else {
                log.info("Sequence database '{}' already exists", dbName);
            }
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
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(rootConfig.jdbcUrl());
        ds.setUsername(rootConfig.getUser());
        ds.setPassword(rootConfig.getPassword());
        return new JdbcTemplate(ds);
    }

    private String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
