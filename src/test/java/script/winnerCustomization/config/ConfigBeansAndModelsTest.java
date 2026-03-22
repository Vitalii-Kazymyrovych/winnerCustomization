package script.winnerCustomization.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.service.WorkflowDefaultsFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBeansAndModelsTest {
    @Test
    void jacksonConfigRegistersJavaTimeModule() throws Exception {
        ObjectMapper objectMapper = new JacksonConfig().objectMapper();

        String json = objectMapper.writeValueAsString(LocalDateTime.of(2026, 3, 22, 12, 30));
        LocalDateTime parsed = objectMapper.readValue(json, LocalDateTime.class);

        assertThat(json).isNotBlank();
        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 3, 22, 12, 30));
    }

    @Test
    void timeConfigProvidesUtcClock() {
        Clock clock = new TimeConfig().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void workflowDefaultsFactoryReturnsSameConfigInstance() {
        AppConfig config = TestFixtures.configWithReportDirectory("");

        assertThat(new WorkflowDefaultsFactory().enrich(config)).isSameAs(config);
    }

    @Test
    void appConfigJdbcUrlsAreBuiltFromFields() {
        AppConfig.DatabaseConfig databaseConfig = new AppConfig.DatabaseConfig();
        databaseConfig.setHost("localhost");
        databaseConfig.setPort(5432);
        databaseConfig.setDb("winner");

        AppConfig.RootDatabaseConfig rootDatabaseConfig = new AppConfig.RootDatabaseConfig();
        rootDatabaseConfig.setHost("root-host");
        rootDatabaseConfig.setPort(6432);
        rootDatabaseConfig.setMaintenanceDb("postgres");

        assertThat(databaseConfig.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/winner");
        assertThat(rootDatabaseConfig.jdbcUrl()).isEqualTo("jdbc:postgresql://root-host:6432/postgres");
    }

    @Test
    void stageWindowDurationOverlapAndAlertDeduplicationWork() {
        SequenceRecord.StageWindow stage = new SequenceRecord.StageWindow(
                "service",
                "Service",
                SequenceRecord.StageType.REAL,
                LocalDateTime.of(2026, 3, 22, 10, 0),
                null,
                false,
                false,
                true
        );
        stage.addAlert("alert");
        stage.addAlert("alert");
        stage.addAlert(" ");

        assertThat(stage.durationText(LocalDateTime.of(2026, 3, 22, 10, 5))).isEqualTo("00:05:00");
        assertThat(stage.overlaps(LocalDateTime.of(2026, 3, 22, 10, 4))).isTrue();
        assertThat(stage.overlaps(LocalDateTime.of(2026, 3, 22, 9, 59))).isFalse();
        assertThat(stage.alerts()).containsExactly("alert");
    }

    @Test
    void notificationAndStagesAreSortedAndDeduplicated() {
        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.of(2026, 3, 22, 8, 0));
        SequenceRecord.StageWindow hidden = new SequenceRecord.StageWindow(
                "hidden", "Hidden", SequenceRecord.StageType.TRANSITIONAL,
                LocalDateTime.of(2026, 3, 22, 8, 5), null, false, false, false);
        SequenceRecord.StageWindow visibleB = new SequenceRecord.StageWindow(
                "b", "B", SequenceRecord.StageType.REAL,
                LocalDateTime.of(2026, 3, 22, 8, 10), null, false, false, true);
        SequenceRecord.StageWindow visibleA = new SequenceRecord.StageWindow(
                "a", "A", SequenceRecord.StageType.REAL,
                LocalDateTime.of(2026, 3, 22, 8, 1), null, false, false, true);
        record.addStage(visibleB);
        record.addStage(hidden);
        record.addStage(visibleA);

        var event = new SequenceRecord.NotificationEvent("AA1111", LocalDateTime.of(2026, 3, 22, 8, 2), "msg");
        record.addNotification(event);
        record.addNotification(event);

        assertThat(record.stagesChronologically()).containsExactly(visibleA, visibleB);
        assertThat(record.getNotifications()).containsExactly(event);
    }
}
