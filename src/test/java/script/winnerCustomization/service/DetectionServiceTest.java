package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionServiceTest {
    @Mock
    private JdbcTemplate sourceJdbc;
    @Mock
    private RuntimeConfig runtimeConfig;

    @InjectMocks
    private DetectionService detectionService;

    @Test
    void shouldBuildSqlFromRuntimeConfigAndMapRows() throws Exception {
        AppConfig appConfig = appConfig("videoanalytics", "alpr_detections");
        when(runtimeConfig.get()).thenReturn(appConfig);

        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 2, 3, 4);
        when(sourceJdbc.query(any(String.class), any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Detection> mapper = invocation.getArgument(1);
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getLong("id")).thenReturn(9L);
            when(rs.getString("plate_number")).thenReturn("AB1234");
            when(rs.getInt("analytics_id")).thenReturn(55);
            when(rs.getObject("direction")).thenReturn(270);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(createdAt));
            return List.of(mapper.mapRow(rs, 0));
        });

        List<Detection> detections = detectionService.loadAllDetections();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(sourceJdbc).query(sqlCaptor.capture(), any(RowMapper.class));
        assertThat(sqlCaptor.getValue()).isEqualTo(
                "select id, plate_number, analytics_id, direction, created_at from videoanalytics.alpr_detections order by created_at asc, id asc"
        );
        assertThat(detections).containsExactly(new Detection(9L, "AB1234", 55, 270, createdAt));
    }

    @Test
    void shouldApplyLoadFromFilterWhenConfigured() {
        AppConfig appConfig = appConfig("videoanalytics", "alpr_detections");
        LocalDateTime loadFrom = LocalDateTime.of(2026, 3, 17, 9, 30);
        appConfig.getSourceTable().setLoadFrom(loadFrom);
        when(runtimeConfig.get()).thenReturn(appConfig);
        when(sourceJdbc.query(any(String.class), any(RowMapper.class), any())).thenReturn(List.of());

        detectionService.loadAllDetections();

        verify(sourceJdbc).query(
                eq("select id, plate_number, analytics_id, direction, created_at from videoanalytics.alpr_detections where created_at >= ? order by created_at asc, id asc"),
                any(RowMapper.class),
                eq(Timestamp.valueOf(loadFrom))
        );
    }

    @Test
    void shouldApplyExplicitDateRangeFilterForDatedReportEndpoint() {
        AppConfig appConfig = appConfig("videoanalytics", "alpr_detections");
        when(runtimeConfig.get()).thenReturn(appConfig);
        when(sourceJdbc.query(any(String.class), any(RowMapper.class), any(), any())).thenReturn(List.of());

        LocalDateTime fromInclusive = LocalDateTime.of(2026, 3, 19, 0, 0);
        LocalDateTime toExclusive = LocalDateTime.of(2026, 3, 20, 0, 0);

        detectionService.loadDetectionsBetween(fromInclusive, toExclusive);

        verify(sourceJdbc).query(
                eq("select id, plate_number, analytics_id, direction, created_at from videoanalytics.alpr_detections where created_at >= ? and created_at < ? order by created_at asc, id asc"),
                any(RowMapper.class),
                eq(Timestamp.valueOf(fromInclusive)),
                eq(Timestamp.valueOf(toExclusive))
        );
    }

    private AppConfig appConfig(String schema, String table) {
        AppConfig appConfig = new AppConfig();
        AppConfig.DatabaseConfig sourceDb = new AppConfig.DatabaseConfig();
        sourceDb.setSchema(schema);
        appConfig.setSourceDatabase(sourceDb);
        AppConfig.SourceTableConfig sourceTable = new AppConfig.SourceTableConfig();
        sourceTable.setTable(table);
        appConfig.setSourceTable(sourceTable);
        return appConfig;
    }
}
