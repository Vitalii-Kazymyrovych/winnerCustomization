package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.repository.NotificationRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsDatasetCsvSnapshotTest {
    private static final LocalDateTime REPORT_GENERATED_AT = LocalDateTime.of(2026, 3, 22, 19, 24, 11);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void productionDatasetMatchesCommittedLogicCsvSnapshot() throws Exception {
        AppConfig config = loadConfig();
        List<Detection> detections = loadDetections();
        List<SequenceRecord> records = new SequenceEngine().build(detections, config, REPORT_GENERATED_AT);

        NotificationService notificationService = new NotificationService(new NotificationRepository() {
            @Override public void initialize() {}
            @Override public void upsertPending(NotificationService.PendingNotification pendingNotification) {}
            @Override public void cancel(String plateNumber, int cameraId, LocalDateTime triggerAt) {}
            @Override public List<NotificationService.PendingNotification> findDuePending(LocalDateTime now, int limit) { return List.of(); }
            @Override public List<NotificationService.PendingNotification> findAll() { return List.of(); }
            @Override public void markSent(long id, LocalDateTime sentAt) {}
        }, new TelegramNotifier(new ObjectMapper()), Clock.fixed(REPORT_GENERATED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

        attachNotifications(records, notificationService.evaluate(detections, config));

        String actual = normalizeLineEndings(String.join("\n", toCsvLines(records)) + "\n");
        String expected = normalizeLineEndings(Files.readString(Path.of("results/expected_sequences_logic.csv")));

        assertThat(actual).isEqualTo(expected);
    }

    private String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<String> toCsvLines(List<SequenceRecord> records) {
        List<String> lines = new ArrayList<>();
        lines.add("plate,sequence_started_at,sequence_finished_at,sequence_closed,stage_name,stage_label,stage_type,in_time,out_time,duration,partial,alerts");
        for (SequenceRecord record : records) {
            for (SequenceRecord.StageWindow stage : record.stagesChronologically()) {
                lines.add(String.join(",",
                        csv(record.getPlateNumber()),
                        csv(format(record.getStartedAt())),
                        csv(format(record.getFinishedAt())),
                        csv(Boolean.toString(record.isClosed())),
                        csv(stage.stageName()),
                        csv(stage.stageLabel()),
                        csv(stage.stageType().name()),
                        csv(format(stage.timeIn())),
                        csv(format(stage.timeOut())),
                        csv(stage.durationText(REPORT_GENERATED_AT)),
                        csv(Boolean.toString(stage.partial())),
                        csv(String.join(" | ", stage.alerts()))
                ));
            }
        }
        return lines;
    }

    private void attachNotifications(List<SequenceRecord> records, List<SequenceRecord.NotificationEvent> notifications) {
        for (SequenceRecord.NotificationEvent notification : notifications) {
            for (SequenceRecord record : records) {
                if (!record.getPlateNumber().equals(notification.plateNumber())) {
                    continue;
                }
                if (record.getStartedAt() != null && !notification.triggeredAt().isBefore(record.getStartedAt())
                        && (record.getFinishedAt() == null || !notification.triggeredAt().isAfter(record.getFinishedAt()))) {
                    record.addNotification(notification);
                    SequenceRecord.StageWindow stage = record.stagesChronologically().stream()
                            .filter(item -> item.overlaps(notification.triggeredAt()))
                            .findFirst()
                            .orElse(null);
                    if (stage != null) {
                        stage.addAlert(notification.message());
                    }
                }
            }
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : TIMESTAMP_FORMATTER.format(value);
    }

    private AppConfig loadConfig() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper.readValue(Path.of("results/config.json.production").toFile(), AppConfig.class);
    }

    private List<Detection> loadDetections() throws Exception {
        List<Detection> detections = new ArrayList<>();
        boolean inCopy = false;
        for (String line : Files.readAllLines(Path.of("results/alpr_detections.sql"))) {
            if (line.startsWith("COPY ")) {
                inCopy = true;
                continue;
            }
            if (!inCopy) {
                continue;
            }
            if ("\\.".equals(line)) {
                break;
            }
            String[] parts = line.split("\\t");
            detections.add(new Detection(
                    Long.parseLong(parts[0]),
                    parts[1],
                    Integer.parseInt(parts[7]),
                    "\\N".equals(parts[12]) ? null : Integer.parseInt(parts[12]),
                    LocalDateTime.parse(parts[10].replace(' ', 'T'))
            ));
        }
        return detections;
    }
}
