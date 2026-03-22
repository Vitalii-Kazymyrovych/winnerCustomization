package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsDatasetSequenceInvariantTest {
    private static final LocalDateTime REPORT_GENERATED_AT = LocalDateTime.of(2026, 3, 22, 19, 24, 11);

    @Test
    void validatesAllPlatesAndAllSequencesAgainstFullResultsDataset() throws Exception {
        AppConfig config = loadConfig();
        List<Detection> detections = loadDetections();

        List<SequenceRecord> records = new SequenceEngine().build(detections, config, REPORT_GENERATED_AT);
        Map<String, List<SequenceRecord>> recordsByPlate = records.stream()
                .collect(Collectors.groupingBy(SequenceRecord::getPlateNumber));

        for (Map.Entry<String, List<Detection>> entry : detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .collect(Collectors.groupingBy(Detection::plateNumber, Collectors.toList()))
                .entrySet()) {
            String plate = entry.getKey();
            List<SequenceRecord> plateRecords = recordsByPlate.getOrDefault(plate, List.of());

            for (SequenceRecord plateRecord : plateRecords) {
                List<SequenceRecord.StageWindow> orderedStages = plateRecord.stagesChronologically();
                for (int index = 1; index < orderedStages.size(); index++) {
                    SequenceRecord.StageWindow previous = orderedStages.get(index - 1);
                    SequenceRecord.StageWindow current = orderedStages.get(index);
                    if (previous.timeOut() != null && current.timeIn() != null) {
                        assertThat(previous.timeOut().isAfter(current.timeIn()))
                                .withFailMessage("Overlapping stages detected for plate %s between %s and %s", plate, previous.stageLabel(), current.stageLabel())
                                .isFalse();
                    }
                }

                List<SequenceRecord.StageWindow> singleStages = orderedStages.stream()
                        .filter(stage -> stage.stageType() == SequenceRecord.StageType.SINGLE_CAMERA)
                        .toList();
                for (SequenceRecord.StageWindow stage : singleStages) {
                    if (stage.timeIn() != null && stage.timeOut() != null) {
                        assertThat(stage.timeOut().isBefore(stage.timeIn()))
                                .withFailMessage("Single-camera stage has negative duration for plate %s: %s", plate, stage.stageName())
                                .isFalse();
                    }
                }
                for (int index = 1; index < singleStages.size(); index++) {
                    SequenceRecord.StageWindow previousSingle = singleStages.get(index - 1);
                    SequenceRecord.StageWindow currentSingle = singleStages.get(index);
                    assertThat(previousSingle.stageName().equals(currentSingle.stageName())
                            && Objects.equals(previousSingle.timeOut(), currentSingle.timeIn()))
                            .withFailMessage("Consecutive duplicate single-camera split detected for plate %s around %s", plate, currentSingle.timeIn())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void productionRegressionsCloseBackyardSequencesAndIgnoreStandaloneTransitionalTriggers() throws Exception {
        AppConfig config = loadConfig();
        List<Detection> detections = loadDetections();

        Map<String, List<SequenceRecord>> recordsByPlate = new SequenceEngine().build(detections, config, REPORT_GENERATED_AT).stream()
                .collect(Collectors.groupingBy(SequenceRecord::getPlateNumber));

        assertThat(recordsByPlate.get("AA2292XT")).singleElement().satisfies(record -> {
            assertThat(record.isClosed()).isTrue();
            assertThat(record.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 3, 17, 12, 59, 47, 223_000_000));
            assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                    .containsExactly("parking", "backyard");
        });

        assertThat(recordsByPlate.get("KA6137MT")).singleElement().satisfies(record -> {
            assertThat(record.isClosed()).isTrue();
            assertThat(record.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 3, 17, 13, 1, 22, 218_000_000));
            assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                    .containsExactly("parking", "drive_in", "backyard");
        });

        assertThat(recordsByPlate.get("KA0082XM")).allSatisfy(record ->
                assertThat(record.stagesChronologically())
                        .extracting(SequenceRecord.StageWindow::stageName)
                        .isNotEqualTo(List.of("backyard")));
        
        assertThat(recordsByPlate.get("KA8611PK")).isNotEmpty();
        assertThat(recordsByPlate.get("KA8611PK").getFirst().stagesChronologically().subList(0, 3))
                .extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("parking", "backyard", "parking");
        assertThat(recordsByPlate.get("KA8611PK").getFirst().stagesChronologically().get(2).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 17, 17, 37, 26, 575_000_000));
        assertThat(recordsByPlate.get("KA8611PK").getFirst().stagesChronologically().get(1).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 17, 15, 41, 31, 500_000_000));

        assertThat(recordsByPlate.get("KA2654TA")).isNotEmpty();
        assertThat(recordsByPlate.get("KA2654TA").getFirst().stagesChronologically().subList(0, 3))
                .extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("parking", "backyard", "parking");
        assertThat(recordsByPlate.get("KA2654TA").getFirst().stagesChronologically().get(2).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 17, 15, 14, 39, 991_000_000));
        assertThat(recordsByPlate.get("KA2654TA").getFirst().stagesChronologically().get(1).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 17, 14, 44, 12, 677_000_000));
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
