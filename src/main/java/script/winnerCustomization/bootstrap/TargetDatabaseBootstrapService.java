package script.winnerCustomization.bootstrap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.AppConfig;
import script.winnerCustomization.config.DatabaseConfig;
import script.winnerCustomization.config.DbConnectionConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Service
public class TargetDatabaseBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(TargetDatabaseBootstrapService.class);
    private final AppConfig appConfig;

    public TargetDatabaseBootstrapService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void bootstrap() {
        DatabaseConfig db = appConfig.getDatabase();
        DbConnectionConfig target = db.getSequence();
        log.info("Bootstrapping target DB host={} port={} db={} schema={}", db.getHost(), db.getPort(), target.getDb(), target.getSchema());

        try {
            ensureDatabaseAndUser(db, target);
            ensureSchemaAndTables(db, target);
            log.info("Target DB bootstrap completed");
        } catch (SQLException ex) {
            log.warn("Target DB bootstrap skipped/failed: {}", ex.getMessage());
        }
    }

    private void ensureDatabaseAndUser(DatabaseConfig db, DbConnectionConfig target) throws SQLException {
        String maintenanceUrl = jdbcUrl(db, db.getMaintenanceDb());
        try (Connection c = DriverManager.getConnection(maintenanceUrl, db.getRootUser(), db.getRootPassword());
             Statement st = c.createStatement()) {
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + target.getUser() + "') THEN CREATE ROLE " + target.getUser() + " LOGIN PASSWORD '" + target.getPassword() + "'; END IF; END $$;");
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '" + target.getDb() + "') THEN CREATE DATABASE " + target.getDb() + " OWNER " + target.getUser() + "; END IF; END $$;");
        }
    }

    private void ensureSchemaAndTables(DatabaseConfig db, DbConnectionConfig target) throws SQLException {
        String targetUrl = jdbcUrl(db, target.getDb());
        try (Connection c = DriverManager.getConnection(targetUrl, db.getRootUser(), db.getRootPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + target.getSchema());
            st.execute("CREATE TABLE IF NOT EXISTS " + target.getSchema() + ".sequences (plate TEXT PRIMARY KEY, closed BOOLEAN NOT NULL, last_detection TIMESTAMP NULL, closed_at_utc TIMESTAMP NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + target.getSchema() + ".stages (id BIGSERIAL PRIMARY KEY, plate TEXT NOT NULL, name TEXT NOT NULL, label TEXT NOT NULL, type TEXT NOT NULL, active BOOLEAN NOT NULL, full BOOLEAN NOT NULL, timeout_seconds INTEGER NOT NULL, in_time TIMESTAMP NULL, out_time TIMESTAMP NULL, last_detection_time TIMESTAMP NULL, duration_seconds BIGINT NULL, alerts TEXT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS " + target.getSchema() + ".alerts (id BIGSERIAL PRIMARY KEY, plate TEXT NOT NULL, trigger_analytics_id INTEGER NOT NULL, timeout_seconds INTEGER NOT NULL, message TEXT NOT NULL, active BOOLEAN NOT NULL, created_at_utc TIMESTAMP NULL)");
            st.execute("GRANT USAGE ON SCHEMA " + target.getSchema() + " TO " + target.getUser());
            st.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA " + target.getSchema() + " TO " + target.getUser());
        }
    }

    private String jdbcUrl(DatabaseConfig db, String databaseName) {
        return "jdbc:postgresql://" + db.getHost() + ":" + db.getPort() + "/" + databaseName;
    }
}
