package script.winnerCustomization.service.logic;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.config.DatabaseConfig;
import script.winnerCustomization.config.DbConnectionConfig;
 
@Service
public class DatabaseBootstrapServiceImpl implements DatabaseBootstrapService {
 
    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrapServiceImpl.class);
    private final ConfigLoader configLoader;
 
    public DatabaseBootstrapServiceImpl(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }
 
    @Override
    public void bootstrap() {
        DatabaseConfig dbConfig = configLoader.getConfig().getDatabase();
        DbConnectionConfig seqConfig = dbConfig.getSequence();
 
        log.info("=== Bootstrapping target database ===");
 
        // Step 1: Connect to maintenance DB with root credentials
        DriverManagerDataSource maintenanceDs = new DriverManagerDataSource();
        maintenanceDs.setDriverClassName("org.postgresql.Driver");
        maintenanceDs.setUrl(dbConfig.getJdbcUrl(dbConfig.getMaintenanceDb()));
        maintenanceDs.setUsername(dbConfig.getRootUser());
        maintenanceDs.setPassword(dbConfig.getRootPassword());
        JdbcTemplate maintenanceJdbc = new JdbcTemplate(maintenanceDs);
 
        // Step 2: Create target database if not exists
        String targetDb = seqConfig.getDb();
        Integer dbExists = maintenanceJdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_database WHERE datname = ?",
                Integer.class, targetDb);
        if (dbExists != null && dbExists == 0) {
            log.info("Creating database: {}", targetDb);
            // Cannot use parameterized queries for DDL, but the value comes from our own config
            maintenanceJdbc.execute("CREATE DATABASE \"" + targetDb + "\"");
        } else {
            log.info("Database '{}' already exists", targetDb);
        }
 
        // Step 3: Connect to target DB with root credentials for schema/user setup
        DriverManagerDataSource targetRootDs = new DriverManagerDataSource();
        targetRootDs.setDriverClassName("org.postgresql.Driver");
        targetRootDs.setUrl(dbConfig.getJdbcUrl(targetDb));
        targetRootDs.setUsername(dbConfig.getRootUser());
        targetRootDs.setPassword(dbConfig.getRootPassword());
        JdbcTemplate targetRootJdbc = new JdbcTemplate(targetRootDs);
 
        // Step 4: Create schema if not exists
        String schema = seqConfig.getSchema();
        log.info("Ensuring schema '{}' exists", schema);
        targetRootJdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
 
        // Step 5: Create user if not exists and grant privileges
        String user = seqConfig.getUser();
        String password = seqConfig.getPassword();
        Integer userExists = targetRootJdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_roles WHERE rolname = ?",
                Integer.class, user);
        if (userExists != null && userExists == 0) {
            log.info("Creating user: {}", user);
            targetRootJdbc.execute("CREATE USER \"" + user + "\" WITH PASSWORD '" + password + "'");
        } else {
            log.info("User '{}' already exists", user);
        }
        targetRootJdbc.execute("GRANT ALL PRIVILEGES ON SCHEMA \"" + schema + "\" TO \"" + user + "\"");
        targetRootJdbc.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA \"" + schema + "\" TO \"" + user + "\"");
        targetRootJdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA \"" + schema +
                "\" GRANT ALL PRIVILEGES ON TABLES TO \"" + user + "\"");
 
        // Step 6: Create tables
        log.info("Creating tables in schema '{}'", schema);
        createTables(targetRootJdbc, schema);
 
        log.info("=== Target database bootstrap complete ===");
    }
 
    private void createTables(JdbcTemplate jdbc, String schema) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + schema + "\".sequences (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "plate_number VARCHAR(15) NOT NULL, " +
                "active BOOLEAN NOT NULL DEFAULT TRUE, " +
                "start_time TIMESTAMP(3), " +
                "close_time TIMESTAMP(3)" +
                ")");
        log.info("Table '{}.sequences' ensured", schema);
 
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + schema + "\".stages (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "sequence_id BIGINT NOT NULL, " +
                "name VARCHAR(100) NOT NULL, " +
                "label VARCHAR(100) NOT NULL, " +
                "type VARCHAR(20) NOT NULL, " +
                "active BOOLEAN NOT NULL DEFAULT TRUE, " +
                "full BOOLEAN NOT NULL DEFAULT FALSE, " +
                "candidate BOOLEAN NOT NULL DEFAULT FALSE, " +
                "timeout INTEGER NOT NULL DEFAULT 0, " +
                "in_time TIMESTAMP(3), " +
                "out_time TIMESTAMP(3), " +
                "duration_seconds BIGINT, " +
                "plate_number VARCHAR(15) NOT NULL" +
                ")");
        log.info("Table '{}.stages' ensured", schema);
 
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + schema + "\".alerts (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "plate_number VARCHAR(15) NOT NULL, " +
                "message TEXT NOT NULL, " +
                "timeout_seconds INTEGER NOT NULL, " +
                "active BOOLEAN NOT NULL DEFAULT TRUE, " +
                "analytics_id INTEGER NOT NULL, " +
                "stage_id BIGINT" +
                ")");
        log.info("Table '{}.alerts' ensured", schema);
    }
}