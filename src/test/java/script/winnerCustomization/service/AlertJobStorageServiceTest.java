package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import script.winnerCustomization.model.AlertJobType;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertJobStorageServiceTest {
    @Test
    void initializeCreatesTableAndIndex() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doNothing().when(jdbcTemplate).execute(anyString());
        AlertJobStorageService service = new AlertJobStorageService(jdbcTemplate);

        service.initialize();

        verify(jdbcTemplate, org.mockito.Mockito.times(2)).execute(anyString());
    }

    @Test
    void upsertCancelFindAndMarkSentDelegateToJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Timestamp.class), anyInt()))
                .thenAnswer(invocation -> List.of(new script.winnerCustomization.model.AlertJobRecord(
                        1L, "AA1111", AlertJobType.DRIVE_IN_OUT_MISSING,
                        LocalDateTime.of(2026, 3, 22, 10, 0),
                        LocalDateTime.of(2026, 3, 22, 10, 5),
                        "message"
                )));
        AlertJobStorageService service = new AlertJobStorageService(jdbcTemplate);

        service.upsertPending("AA1111", AlertJobType.DRIVE_IN_OUT_MISSING,
                LocalDateTime.of(2026, 3, 22, 10, 0),
                LocalDateTime.of(2026, 3, 22, 10, 5),
                "message");
        service.cancel("AA1111", AlertJobType.DRIVE_IN_OUT_MISSING, LocalDateTime.of(2026, 3, 22, 10, 0));
        assertThat(service.findDuePending(LocalDateTime.of(2026, 3, 22, 11, 0), 10)).hasSize(1);
        service.markSent(1L, LocalDateTime.of(2026, 3, 22, 11, 0));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(), any(), any(), any(), any());
        verify(jdbcTemplate).update(anyString(), any(), any(), any());
        assertThat(sqlCaptor.getValue()).contains("insert into alert_jobs");
    }
}
