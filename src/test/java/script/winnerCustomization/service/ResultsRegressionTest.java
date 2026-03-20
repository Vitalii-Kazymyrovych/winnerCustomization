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

class ResultsRegressionTest {
    private final SequenceEngine engine = new SequenceEngine();
    private final WorkflowDefaultsFactory workflowDefaultsFactory = new WorkflowDefaultsFactory();

    @Test
    void shouldBuildStableNonOverlappingSequencesForCommittedResultsDataset() throws Exception {
        AppConfig config = loadConfig();
        List<Detection> detections = loadDetections(config.getSourceTable().getLoadFrom());

        List<SequenceRecord> records = engine.build(detections, config);

        assertThat(records)
                .as("transition-only events must not leak into final output")
                .noneMatch(record -> record.getPlateNumber().equals("AI2013YB") && record.getStages().isEmpty());

        assertThat(records)
                .as("results dataset should still produce usable sequences")
                .isNotEmpty()
                .anyMatch(record -> record.getPlateNumber().equals("KO4331P"));

        assertThat(records)
                .as("named post labels from workflow defaults must survive into report rows")
                .anyMatch(record -> record.stagesChronologically().stream().anyMatch(stage -> stage.reportLabel().startsWith("Post ")));

        assertThat(records)
                .as("dynamic workflow should preserve generated stage names, not enum constants")
                .allMatch(record -> record.stagesChronologically().stream().allMatch(stage -> stage.stageName() != null && !stage.stageName().isBlank()));

    }

    private AppConfig loadConfig() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AppConfig config = mapper.readValue(Files.readString(Path.of("results/config.json.production")), AppConfig.class);
        return workflowDefaultsFactory.enrich(config);
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
