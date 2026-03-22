package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.TestFixtures;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceEngineAdditionalTest {
    private final SequenceEngine engine = new SequenceEngine();

    @Test
    void returnsEmptyWhenConfigMissingAndSupportsDirectionWrapAround() {
        assertThat(engine.build(List.of(), null, LocalDateTime.now())).isEmpty();

        AppConfig config = TestFixtures.configWithReportDirectory("");
        config.getRealStages().getFirst().getInTriggers().getFirst().getDirectionRange().setFrom(270);
        config.getRealStages().getFirst().getInTriggers().getFirst().getDirectionRange().setTo(90);
        List<Detection> detections = List.of(new Detection(1, "AA1111", 1001, 350, LocalDateTime.of(2026, 3, 22, 10, 0)));

        assertThat(engine.build(detections, config, LocalDateTime.of(2026, 3, 22, 11, 0)).getFirst().stagesChronologically())
                .extracting(script.winnerCustomization.model.SequenceRecord.StageWindow::stageName)
                .containsExactly("drive_in");
    }

    @Test
    void duplicateSuppressionNormalizationAndPendingCandidateBehaviorsAreApplied() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        config.setAllowTransitionalAfterSingleCamera(true);
        config.getTransitionalStages().getFirst().setShowInReportIfIncomplete(true);
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(3, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 22, 10, 2)),
                new Detection(4, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 22, 10, 40))
        );

        var records = engine.build(detections, config, LocalDateTime.of(2026, 3, 22, 11, 0));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().stagesChronologically()).extracting(script.winnerCustomization.model.SequenceRecord.StageWindow::stageName)
                .contains("drive_in", "post_1", "backyard");
        assertThat(records.getFirst().isClosed()).isFalse();
    }
}
