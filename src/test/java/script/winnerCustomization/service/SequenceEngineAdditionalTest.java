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
                .containsExactly("drive_in", "post_1");
        assertThat(records.getFirst().isClosed()).isTrue();
    }

    @Test
    void transitionalCameraTriggersRequireAllowedPreviousStage() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 22, 10, 5))
        );

        var records = engine.build(detections, config, LocalDateTime.of(2026, 3, 22, 11, 0));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().stagesChronologically())
                .extracting(script.winnerCustomization.model.SequenceRecord.StageWindow::stageName)
                .containsExactly("service");
    }

    @Test
    void transitionalStagesWithZeroSequenceTimeoutCloseImmediatelyAfterMaterialization() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 22, 10, 5))
        );

        var records = engine.build(detections, config, LocalDateTime.of(2026, 3, 22, 11, 0));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().isClosed()).isTrue();
        assertThat(records.getFirst().getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5, 2));
        assertThat(records.getFirst().stagesChronologically().getLast().stageName()).isEqualTo("backyard");
        assertThat(records.getFirst().stagesChronologically().getLast().timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5, 2));
    }

    @Test
    void createsTransitionalCandidateImmediatelyAfterAllowedRealStageEndsWithoutTriggerCameraEvent() {
        AppConfig config = TestFixtures.configWithReportDirectory("");

        AppConfig.RealStageConfig parking = new AppConfig.RealStageConfig();
        parking.setName("parking");
        parking.setLabel("Parking");
        parking.setInTriggers(List.of(trigger(1201)));
        parking.setOutTriggers(List.of(trigger(1202)));
        config.setRealStages(List.of(parking));

        AppConfig.TransitionalStageConfig backyard = new AppConfig.TransitionalStageConfig();
        backyard.setName("backyard");
        backyard.setLabel("Backyard");
        backyard.setTriggerCameras(List.of(1999));
        backyard.setCandidateTimeoutSeconds(2);
        backyard.setAllowedAfter(List.of("parking"));
        backyard.setSequenceCloseTimeoutOverrideSeconds(0);
        backyard.setShowInReportIfIncomplete(false);
        config.setTransitionalStages(List.of(backyard));
        config.setSingleCameraStages(List.of());

        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1201, null, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1202, null, LocalDateTime.of(2026, 3, 22, 10, 5))
        );

        var records = engine.build(detections, config, LocalDateTime.of(2026, 3, 22, 11, 0));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().stagesChronologically())
                .extracting(script.winnerCustomization.model.SequenceRecord.StageWindow::stageName)
                .containsExactly("parking", "backyard");
        assertThat(records.getFirst().stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5, 1));
        assertThat(records.getFirst().stagesChronologically().get(1).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5, 3));
        assertThat(records.getFirst().isClosed()).isTrue();
    }

    private AppConfig.CameraTrigger trigger(int cameraId) {
        AppConfig.CameraTrigger trigger = new AppConfig.CameraTrigger();
        trigger.setCameraId(cameraId);
        return trigger;
    }
}
