package script.winnerCustomization.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresDatabaseOperationsIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    private final DatabaseBootstrapService databaseBootstrapService = new DatabaseBootstrapService();

    @BeforeEach
    void prepareSourceData() {
        JdbcTemplate sourceJdbc = new JdbcTemplate(dataSource("videoanalytics"));
        sourceJdbc.execute("create schema if not exists videoanalytics");
        sourceJdbc.execute("""
                create table if not exists videoanalytics.alpr_detections (
                    id bigserial primary key,
                    plate_number varchar(32) not null,
                    analytics_id int not null,
                    direction int null,
                    created_at timestamp not null
                )
                """);
        sourceJdbc.update("delete from videoanalytics.alpr_detections");
        sourceJdbc.update("""
                insert into videoanalytics.alpr_detections(plate_number, analytics_id, direction, created_at)
                values ('AA1111', 1001, 90, timestamp '2026-03-15 10:00:00'),
                       ('BB2222', 1002, 180, timestamp '2026-03-15 10:05:00')
                """);
    }

    @Test
    void shouldCreateSequenceDatabaseAndPerformReadAndWriteOperations() {
        AppConfig.RootDatabaseConfig rootConfig = rootConfig();
        AppConfig.DatabaseConfig sequenceConfig = databaseConfig("sequences_it", "public");
        AppConfig.DatabaseConfig sourceConfig = databaseConfig("videoanalytics", "videoanalytics");

        databaseBootstrapService.ensureDatabaseExists(rootConfig, sequenceConfig);

        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        AppConfig appConfig = new AppConfig();
        appConfig.setSourceDatabase(sourceConfig);
        appConfig.setSequenceDatabase(sequenceConfig);
        AppConfig.SourceTableConfig sourceTable = new AppConfig.SourceTableConfig();
        sourceTable.setTable("alpr_detections");
        appConfig.setSourceTable(sourceTable);
        when(runtimeConfig.get()).thenReturn(appConfig);

        DetectionService detectionService = new DetectionService(new JdbcTemplate(dataSource("videoanalytics")), runtimeConfig);
        List<Detection> detections = detectionService.loadAllDetections();

        assertEquals(2, detections.size());
        assertEquals("AA1111", detections.getFirst().plateNumber());

        SequenceStorageService sequenceStorageService = new SequenceStorageService(
                new JdbcTemplate(dataSource("sequences_it")),
                runtimeConfig
        );
        sequenceStorageService.initialize();

        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.parse("2026-03-15T10:00:00"));
        record.setDriveInOutAt(LocalDateTime.parse("2026-03-15T10:10:00"));
        record.setFinishedAt(LocalDateTime.parse("2026-03-15T10:12:00"));
        record.addAlert("Exceeded 15 min");
        sequenceStorageService.replaceAll(List.of(record));

        JdbcTemplate sequenceJdbc = new JdbcTemplate(dataSource("sequences_it"));
        Integer count = sequenceJdbc.queryForObject("select count(*) from vehicle_sequences", Integer.class);
        String plate = sequenceJdbc.queryForObject("select plate_number from vehicle_sequences limit 1", String.class);

        assertNotNull(count);
        assertEquals(1, count);
        assertEquals("AA1111", plate);
    }

    private AppConfig.RootDatabaseConfig rootConfig() {
        AppConfig.RootDatabaseConfig config = new AppConfig.RootDatabaseConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setUser(USER);
        config.setPassword(PASSWORD);
        config.setMaintenanceDb("postgres");
        return config;
    }

    private AppConfig.DatabaseConfig databaseConfig(String dbName, String schema) {
        AppConfig.DatabaseConfig config = new AppConfig.DatabaseConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setDb(dbName);
        config.setSchema(schema);
        config.setUser(USER);
        config.setPassword(PASSWORD);
        return config;
    }

    private DataSource dataSource(String dbName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + HOST + ":" + PORT + "/" + dbName);
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }
}
