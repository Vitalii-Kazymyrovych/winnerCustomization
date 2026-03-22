package script.winnerCustomization.repository;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.config.TestFixtures;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.service.NotificationService;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcRepositoriesTest {
    @Test
    void detectionRepositoryBuildsExpectedQueries() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        var config = TestFixtures.configWithReportDirectory("");
        when(runtimeConfig.get()).thenReturn(config);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of());

        JdbcDetectionRepository repository = new JdbcDetectionRepository(jdbcTemplate, runtimeConfig);
        repository.findAll();
        config.getSourceTable().setLoadFrom(LocalDateTime.of(2026, 3, 20, 0, 0));
        repository.findAll();
        repository.findBetween(LocalDateTime.of(2026, 3, 21, 0, 0), LocalDateTime.of(2026, 3, 22, 0, 0));

        verify(jdbcTemplate).query(Mockito.contains("from videoanalytics.alpr_detections order by"), any(org.springframework.jdbc.core.RowMapper.class));
        verify(jdbcTemplate).query(Mockito.contains("where created_at >= ? order by"), any(org.springframework.jdbc.core.RowMapper.class), any(Timestamp.class));
        verify(jdbcTemplate).query(Mockito.contains("where created_at >= ? and created_at < ?"), any(org.springframework.jdbc.core.RowMapper.class), any(Timestamp.class), any(Timestamp.class));
    }

    @Test
    void detectionRepositoryMapperConvertsResultSet() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        when(runtimeConfig.get()).thenReturn(TestFixtures.configWithReportDirectory(""));
        JdbcDetectionRepository repository = new JdbcDetectionRepository(jdbcTemplate, runtimeConfig);
        var mapperMethod = JdbcDetectionRepository.class.getDeclaredMethod("mapper");
        mapperMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        var mapper = (org.springframework.jdbc.core.RowMapper<Detection>) mapperMethod.invoke(repository);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(1L);
        when(resultSet.getString("plate_number")).thenReturn("AA1111");
        when(resultSet.getInt("analytics_id")).thenReturn(1001);
        when(resultSet.getObject("direction")).thenReturn(90);
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 22, 10, 0)));

        Detection detection = mapper.mapRow(resultSet, 0);

        assertThat(detection).isEqualTo(new Detection(1L, "AA1111", 1001, 90, LocalDateTime.of(2026, 3, 22, 10, 0)));
    }

    @Test
    void notificationRepositoryCoversAllQueries() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doNothing().when(jdbcTemplate).execute(anyString());
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Timestamp.class), anyInt()))
                .thenReturn(List.of(new NotificationService.PendingNotification(1L, "AA1111", 1001,
                        LocalDateTime.of(2026, 3, 22, 10, 0),
                        LocalDateTime.of(2026, 3, 22, 10, 5),
                        "message")));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        JdbcNotificationRepository repository = new JdbcNotificationRepository(jdbcTemplate);
        repository.initialize();
        repository.upsertPending(new NotificationService.PendingNotification(0L, "AA1111", 1001,
                LocalDateTime.of(2026, 3, 22, 10, 0),
                LocalDateTime.of(2026, 3, 22, 10, 5),
                "message"));
        repository.cancel("AA1111", 1001, LocalDateTime.of(2026, 3, 22, 10, 0));
        assertThat(repository.findDuePending(LocalDateTime.of(2026, 3, 22, 11, 0), 10)).hasSize(1);
        assertThat(repository.findAll()).isEmpty();
        repository.markSent(1L, LocalDateTime.of(2026, 3, 22, 11, 0));
    }

    @Test
    void sequenceRepositoryInitializesAndPersistsStages() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doNothing().when(jdbcTemplate).execute(anyString());
        when(jdbcTemplate.update(anyString())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(10L);
        JdbcSequenceRepository repository = new JdbcSequenceRepository(jdbcTemplate);
        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.of(2026, 3, 22, 10, 0));
        record.setClosed(true);
        record.setFinishedAt(LocalDateTime.of(2026, 3, 22, 11, 0));
        record.addStage(new SequenceRecord.StageWindow("service", "Service", SequenceRecord.StageType.REAL,
                LocalDateTime.of(2026, 3, 22, 10, 0), LocalDateTime.of(2026, 3, 22, 11, 0), false, false, true));

        repository.initialize();
        repository.replaceAll(List.of(record));

        verify(jdbcTemplate, Mockito.times(2)).execute(anyString());
        assertThatThrownBy(repository::findAll).isInstanceOf(UnsupportedOperationException.class);
    }
}
