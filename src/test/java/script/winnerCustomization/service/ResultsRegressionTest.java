package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ResultsRegressionTest {
    private final SequenceEngine engine = new SequenceEngine();

    @Test
    void shouldBuildExpectedSequencesForCommittedResultsDataset() throws Exception {
        AppConfig config = loadConfig();
        List<Detection> detections = loadDetections(config.getSourceTable().getLoadFrom());

        List<SequenceRecord> records = engine.build(detections, config);

        assertThat(records)
                .as("transition-only events must not leak into final output")
                .noneMatch(record -> record.getPlateNumber().equals("AI2013YB"));

        assertThat(records)
                .as("wrapped parking directions should no longer disappear from the report")
                .anyMatch(record -> record.getPlateNumber().equals("KO4331P"));

        SequenceRecord ka3915ea = findRecord(records, "KA3915EA", LocalDateTime.of(2026, 3, 17, 13, 21, 42, 546_000_000));
        assertThat(ka3915ea.stagesChronologically())
                .extracting(StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut, StageWindow::alert)
                .containsExactly(
                        tuple("Service",
                                LocalDateTime.of(2026, 3, 17, 13, 21, 42, 546_000_000),
                                LocalDateTime.of(2026, 3, 17, 13, 39, 3, 239_000_000),
                                "No Post in within 15 minutes"),
                        tuple("Test-Drive",
                                LocalDateTime.of(2026, 3, 17, 13, 39, 4, 239_000_000),
                                LocalDateTime.of(2026, 3, 17, 14, 37, 9, 479_000_000),
                                ""),
                        tuple("Backyard",
                                LocalDateTime.of(2026, 3, 17, 14, 37, 10, 479_000_000),
                                null,
                                ""));

        SequenceRecord ki0678ac = findRecord(records, "KI0678AC", LocalDateTime.of(2026, 3, 18, 9, 57, 38, 804_000_000));
        assertThat(ki0678ac.stagesChronologically())
                .extracting(StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut, StageWindow::alert)
                .containsExactly(
                        tuple("Backyard",
                                LocalDateTime.of(2026, 3, 18, 9, 57, 38, 804_000_000),
                                LocalDateTime.of(2026, 3, 18, 9, 58, 8, 64_000_000),
                                ""),
                        tuple("Service",
                                LocalDateTime.of(2026, 3, 18, 9, 58, 9, 64_000_000),
                                LocalDateTime.of(2026, 3, 18, 15, 44, 36, 580_000_000),
                                "No Post in within 15 minutes"),
                        tuple("Test-Drive",
                                LocalDateTime.of(2026, 3, 18, 15, 44, 37, 580_000_000),
                                LocalDateTime.of(2026, 3, 18, 15, 57, 56, 289_000_000),
                                ""),
                        tuple("Backyard",
                                LocalDateTime.of(2026, 3, 18, 15, 57, 57, 289_000_000),
                                null,
                                ""));

        SequenceRecord shortPlate = findRecord(records, "1163KK", LocalDateTime.of(2026, 3, 18, 15, 55, 7, 697_000_000));
        assertThat(shortPlate.stagesChronologically())
                .extracting(StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut, StageWindow::alert)
                .containsExactly(
                        tuple("Service", null, LocalDateTime.of(2026, 3, 18, 15, 55, 6, 697_000_000), ""),
                        tuple("Post 1", LocalDateTime.of(2026, 3, 18, 15, 55, 7, 697_000_000), null, ""));

        assertNoOverlaps(records);
    }

    private void assertNoOverlaps(List<SequenceRecord> records) {
        for (SequenceRecord record : records) {
            List<StageWindow> stages = record.stagesChronologically().stream()
                    .filter(stage -> stage.timeIn() != null && stage.timeOut() != null)
                    .toList();
            for (int i = 1; i < stages.size(); i++) {
                StageWindow previous = stages.get(i - 1);
                StageWindow current = stages.get(i);
                assertThat(previous.timeOut().isAfter(current.timeIn()))
                        .as("plate %s must not have overlapping stages between %s and %s",
                                record.getPlateNumber(), previous.reportLabel(), current.reportLabel())
                        .isFalse();
            }
        }
    }

    private SequenceRecord findRecord(List<SequenceRecord> records, String plate, LocalDateTime startedAt) {
        return records.stream()
                .filter(record -> record.getPlateNumber().equals(plate))
                .filter(record -> record.getStartedAt().equals(startedAt))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Record not found for plate " + plate + " at " + startedAt));
    }

    private AppConfig loadConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return mapper.readValue(Files.readString(Path.of("results/config.json.production")), AppConfig.class);
    }

    private List<Detection> loadDetections(LocalDateTime loadFrom) throws Exception {
        List<Detection> detections = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("results/alpr_detections.sql"))) {
            if (line.isBlank() || !Character.isDigit(line.charAt(0))) {
                continue;
            }
            String[] parts = line.split("\t");
            LocalDateTime createdAt = Timestamp.valueOf(parts[10]).toLocalDateTime();
            if (loadFrom != null && createdAt.isBefore(loadFrom)) {
                continue;
            }
            detections.add(new Detection(
                    Long.parseLong(parts[0]),
                    parts[1],
                    Integer.parseInt(parts[7]),
                    Integer.parseInt(parts[12]),
                    createdAt
            ));
        }
        detections.sort(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id));
        return detections;
    }
}
