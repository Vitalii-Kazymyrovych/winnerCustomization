package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
        Map<String, AppConfig.SingleCameraStageConfig> singleByCamera = config.getSingleCameraStages().stream()
                .collect(Collectors.toMap(stage -> String.valueOf(stage.getCameraId()), stage -> stage));

        for (Map.Entry<String, List<Detection>> entry : detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .collect(Collectors.groupingBy(Detection::plateNumber, Collectors.toList()))
                .entrySet()) {
            String plate = entry.getKey();
            List<Detection> plateDetections = entry.getValue();
            List<SequenceRecord> plateRecords = recordsByPlate.getOrDefault(plate, List.of());
            List<SequenceRecord.StageWindow> actualSingleStages = plateRecords.stream()
                    .flatMap(record -> record.stagesChronologically().stream())
                    .filter(stage -> stage.stageType() == SequenceRecord.StageType.SINGLE_CAMERA)
                    .toList();

            List<ExpectedSingleStage> expectedSingleStages = deriveExpectedSingleStages(plateDetections, singleByCamera, config.getSequenceCloseTimeoutMinutes());

            assertThat(actualSingleStages)
                    .withFailMessage("Single-camera stage count mismatch for plate %s", plate)
                    .hasSize(expectedSingleStages.size());

            for (int index = 0; index < expectedSingleStages.size(); index++) {
                ExpectedSingleStage expected = expectedSingleStages.get(index);
                SequenceRecord.StageWindow actual = actualSingleStages.get(index);
                assertThat(actual.stageName())
                        .withFailMessage("Unexpected single-camera stage name for plate %s at index %s", plate, index)
                        .isEqualTo(expected.stageName());
                assertThat(actual.timeIn())
                        .withFailMessage("Unexpected single-camera stage start for plate %s at index %s", plate, index)
                        .isEqualTo(expected.timeIn());
                assertThat(actual.timeOut())
                        .withFailMessage("Unexpected single-camera stage end for plate %s at index %s", plate, index)
                        .isEqualTo(expected.timeOut());
            }

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
            }
        }
    }

    private List<ExpectedSingleStage> deriveExpectedSingleStages(List<Detection> detections,
                                                                 Map<String, AppConfig.SingleCameraStageConfig> singleByCamera,
                                                                 Integer sequenceCloseTimeoutMinutes) {
        List<ExpectedSingleStage> expected = new ArrayList<>();
        ActiveRun activeRun = null;
        Detection previousDetection = null;
        long sequenceTimeoutSeconds = Math.max(1, (sequenceCloseTimeoutMinutes == null ? 2880 : sequenceCloseTimeoutMinutes) * 60L);

        for (Detection detection : detections) {
            if (previousDetection != null
                    && Duration.between(previousDetection.createdAt(), detection.createdAt()).toSeconds() > sequenceTimeoutSeconds) {
                finalizeRun(activeRun, expected);
                activeRun = null;
            }

            AppConfig.SingleCameraStageConfig singleConfig = singleByCamera.get(String.valueOf(detection.analyticsId()));
            if (singleConfig == null) {
                if (activeRun != null) {
                    expected.add(new ExpectedSingleStage(activeRun.stageName, activeRun.timeIn, detection.createdAt()));
                    activeRun = null;
                }
            } else if (activeRun == null) {
                activeRun = new ActiveRun(singleConfig.getName(), detection.createdAt(), detection.createdAt(), singleConfig.getTimeoutSeconds());
            } else if (Objects.equals(activeRun.stageName, singleConfig.getName())) {
                activeRun.lastSeenAt = detection.createdAt();
            } else {
                expected.add(new ExpectedSingleStage(activeRun.stageName, activeRun.timeIn, detection.createdAt()));
                activeRun = new ActiveRun(singleConfig.getName(), detection.createdAt(), detection.createdAt(), singleConfig.getTimeoutSeconds());
            }
            previousDetection = detection;
        }

        finalizeRun(activeRun, expected);
        return expected;
    }

    private void finalizeRun(ActiveRun activeRun, List<ExpectedSingleStage> expected) {
        if (activeRun == null) {
            return;
        }
        LocalDateTime timeOut = Duration.between(activeRun.lastSeenAt, REPORT_GENERATED_AT).toSeconds() > activeRun.timeoutSeconds
                ? activeRun.lastSeenAt
                : null;
        expected.add(new ExpectedSingleStage(activeRun.stageName, activeRun.timeIn, timeOut));
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

    private static final class ActiveRun {
        private final String stageName;
        private final LocalDateTime timeIn;
        private LocalDateTime lastSeenAt;
        private final int timeoutSeconds;

        private ActiveRun(String stageName, LocalDateTime timeIn, LocalDateTime lastSeenAt, Integer timeoutSeconds) {
            this.stageName = stageName;
            this.timeIn = timeIn;
            this.lastSeenAt = lastSeenAt;
            this.timeoutSeconds = timeoutSeconds == null ? 0 : timeoutSeconds;
        }
    }

    private record ExpectedSingleStage(String stageName, LocalDateTime timeIn, LocalDateTime timeOut) {}
}
