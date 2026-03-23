package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.TestFixtures;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

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

        SequenceRecord record = engine.build(
                List.of(new Detection(1, "AA1111", 1001, 350, LocalDateTime.of(2026, 3, 22, 10, 0))),
                config,
                LocalDateTime.of(2026, 3, 22, 11, 0))
                .getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("drive_in");
    }

    @Test
    void duplicateSuppressionKeepsOnlyMeaningfulStateChanges() {
        AppConfig config = TestFixtures.configWithReportDirectory("");

        SequenceRecord record = engine.build(List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(3, "AA1111", 1002, 200, LocalDateTime.of(2026, 3, 22, 10, 5))
        ), config, LocalDateTime.of(2026, 3, 22, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(1);
        assertThat(record.stagesChronologically().getFirst().timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 0));
        assertThat(record.stagesChronologically().getFirst().timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5));
    }

    @Test
    void transitionalCameraTriggersRequireAllowedPreviousStage() {
        AppConfig config = TestFixtures.configWithReportDirectory("");

        SequenceRecord record = engine.build(List.of(
                new Detection(1, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 22, 10, 5))
        ), config, LocalDateTime.of(2026, 3, 22, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("service");
    }

    @Test
    void transitionalStageCreatedAfterRealOutCanCloseSequenceImmediatelyAndStayHidden() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        AppConfig.RealStageConfig parking = new AppConfig.RealStageConfig();
        parking.setName("parking");
        parking.setLabel("Parking");
        parking.setInTriggers(List.of(trigger(1201)));
        parking.setOutTriggers(List.of(trigger(1202)));
        config.setRealStages(List.of(parking));
        config.setSingleCameraStages(List.of());

        AppConfig.TransitionalStageConfig backyard = new AppConfig.TransitionalStageConfig();
        backyard.setName("backyard");
        backyard.setLabel("Backyard");
        backyard.setCandidateTimeoutSeconds(2);
        backyard.setAllowedAfter(List.of("parking"));
        backyard.setSequenceCloseTimeoutOverrideSeconds(0);
        backyard.setShowInReportIfIncomplete(false);
        config.setTransitionalStages(List.of(backyard));

        SequenceRecord record = engine.build(List.of(
                new Detection(1, "AA1111", 1201, null, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1202, null, LocalDateTime.of(2026, 3, 22, 10, 5))
        ), config, LocalDateTime.of(2026, 3, 22, 11, 0)).getFirst();

        assertThat(record.isClosed()).isTrue();
        assertThat(record.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5, 2));
        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("parking");
        assertThat(record.stagesChronologically().getFirst().timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 5));
    }

    @Test
    void materializedTransitionalStageStillAppearsWhenLaterConcreteStageStarts() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        config.getTransitionalStages().getFirst().setShowInReportIfIncomplete(false);
        config.getTransitionalStages().getFirst().setSequenceCloseTimeoutOverrideSeconds(3600);

        SequenceRecord record = engine.build(List.of(
                new Detection(1, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1005, 10, LocalDateTime.of(2026, 3, 22, 10, 1)),
                new Detection(3, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 22, 10, 10))
        ), config, LocalDateTime.of(2026, 3, 22, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("service", "backyard", "service");
        assertThat(record.stagesChronologically().get(1).timeIn())
                .isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 1, 1));
        assertThat(record.stagesChronologically().get(1).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 9, 59));
    }

    @Test
    void partialRealOutClosesMaterializedTransitionalAtPreviousSecond() {
        AppConfig config = TestFixtures.configWithReportDirectory("");
        config.getTransitionalStages().getFirst().setShowInReportIfIncomplete(true);
        config.getTransitionalStages().getFirst().setSequenceCloseTimeoutOverrideSeconds(3600);

        SequenceRecord record = engine.build(List.of(
                new Detection(1, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 22, 10, 0)),
                new Detection(2, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 22, 10, 5)),
                new Detection(3, "AA1111", 1005, 10, LocalDateTime.of(2026, 3, 22, 10, 8))
        ), config, LocalDateTime.of(2026, 3, 22, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("service", "backyard", "service");
        assertThat(record.stagesChronologically().get(1).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 22, 10, 7, 59));
        assertThat(record.stagesChronologically().get(2).partial()).isTrue();
    }

    private AppConfig.CameraTrigger trigger(int cameraId) {
        AppConfig.CameraTrigger trigger = new AppConfig.CameraTrigger();
        trigger.setCameraId(cameraId);
        return trigger;
    }
}
