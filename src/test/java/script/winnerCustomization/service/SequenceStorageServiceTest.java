package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.SequenceRecord;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SequenceStorageServiceTest {
    @Mock
    private JdbcTemplate sequenceJdbc;

    @Mock
    private RuntimeConfig runtimeConfig;

    @Test
    void shouldCreateTableOnInitialize() {
        SequenceStorageService service = new SequenceStorageService(sequenceJdbc, runtimeConfig);

        service.initialize();

        verify(sequenceJdbc).execute(contains("create table if not exists vehicle_sequences"));
    }

    @Test
    void shouldReplaceAllRowsWithProvidedRecords() {
        SequenceStorageService service = new SequenceStorageService(sequenceJdbc, runtimeConfig);
        SequenceRecord finished = new SequenceRecord("AA1111", LocalDateTime.of(2026, 1, 1, 10, 0));
        finished.setDriveInOutAt(LocalDateTime.of(2026, 1, 1, 10, 10));
        finished.setFinishedAt(LocalDateTime.of(2026, 1, 1, 11, 0));
        finished.addAlert("slow");

        SequenceRecord open = new SequenceRecord("BB2222", LocalDateTime.of(2026, 1, 1, 12, 0));
        open.setServiceInAt(LocalDateTime.of(2026, 1, 1, 12, 5));

        service.replaceAll(List.of(finished, open));

        verify(sequenceJdbc).update("delete from vehicle_sequences");
        verify(sequenceJdbc, times(2)).update(eq("""
                            insert into vehicle_sequences(plate_number, started_at, finished_at, path, stage_durations, alerts)
                            values (?, ?, ?, ?, ?, ?)
                            """), any(), any(), any(), any(), any(), any());
    }
}
