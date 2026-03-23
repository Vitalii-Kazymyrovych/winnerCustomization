package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceEngineTest {
    private final SequenceEngine engine = new SequenceEngine();

    @Test
    void realStagesDeduplicateRepeatedInAndReopenAfterStickyOut() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 1, 10, 1)),
                new Detection(3, "AA1111", 1005, 200, LocalDateTime.of(2026, 3, 1, 10, 10)),
                new Detection(4, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 1, 10, 12))
        );

        AppConfig config = TestConfigFactory.config();
        config.setTransitionalStages(List.of());

        SequenceRecord record = engine.build(detections, config, LocalDateTime.of(2026, 3, 1, 10, 30)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(2);
        assertThat(record.stagesChronologically().get(0).stageName()).isEqualTo("service");
        assertThat(record.stagesChronologically().get(0).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 0));
        assertThat(record.stagesChronologically().get(0).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 10));
        assertThat(record.stagesChronologically().get(1).stageName()).isEqualTo("service");
        assertThat(record.stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 12));
        assertThat(record.stagesChronologically().get(1).timeOut()).isNull();
        assertThat(record.isClosed()).isFalse();
    }

    @Test
    void reopeningSameRealStageBeforeCandidateTimeoutCancelsPendingTransitional() {
        AppConfig config = TestConfigFactory.config();
        config.getTransitionalStages().getFirst().setShowInReportIfIncomplete(true);
        config.getTransitionalStages().getFirst().setSequenceCloseTimeoutOverrideSeconds(3600);
        config.getTransitionalStages().getFirst().setCandidateTimeoutSeconds(300);

        SequenceRecord record = engine.build(List.of(
                new Detection(1, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1005, 10, LocalDateTime.of(2026, 3, 1, 10, 10)),
                new Detection(3, "AA1111", 1003, 10, LocalDateTime.of(2026, 3, 1, 10, 14))
        ), config, LocalDateTime.of(2026, 3, 1, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("service", "service");
    }

    @Test
    void partialRealOutDoesNotDestroyActiveSingleCameraStage() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1005, 10, LocalDateTime.of(2026, 3, 1, 10, 5))
        );

        AppConfig config = TestConfigFactory.config();
        config.setTransitionalStages(List.of());
        config.getSingleCameraStages().getFirst().setTimeoutSeconds(3600);

        SequenceRecord record = engine.build(detections, config, LocalDateTime.of(2026, 3, 1, 10, 10)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(2);
        assertThat(record.stagesChronologically().get(0).stageName()).isEqualTo("post_1");
        assertThat(record.stagesChronologically().get(0).timeOut()).isNull();
        assertThat(record.stagesChronologically().get(1).stageName()).isEqualTo("service");
        assertThat(record.stagesChronologically().get(1).partial()).isTrue();
        assertThat(record.stagesChronologically().get(1).timeIn()).isNull();
        assertThat(record.stagesChronologically().get(1).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 5));
    }

    @Test
    void transitionalCandidateFromCameraResetsTimeoutAndClosesOnNextStageStart() {
        AppConfig config = TestConfigFactory.config();
        config.getTransitionalStages().getFirst().setShowInReportIfIncomplete(true);
        config.getTransitionalStages().getFirst().setSequenceCloseTimeoutOverrideSeconds(3600);

        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 1, 10, 5)),
                new Detection(3, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 1, 10, 6)),
                new Detection(4, "AA1111", 1003, 90, LocalDateTime.of(2026, 3, 1, 10, 10))
        );

        List<SequenceRecord> records = engine.build(detections, config, LocalDateTime.of(2026, 3, 1, 11, 0));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("drive_in", "backyard", "service");
        assertThat(records.getFirst().stagesChronologically().get(1).timeIn())
                .isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 5));
        assertThat(records.getFirst().stagesChronologically().get(1).timeOut())
                .isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 9, 59));
    }

    @Test
    void transitionsCanInsertTransitionalStageBetweenDifferentConcreteStages() {
        AppConfig config = TestConfigFactory.config();
        config.getTransitionalStages().getFirst().setShowInReportIfIncomplete(true);
        config.getTransitionalStages().getFirst().setSequenceCloseTimeoutOverrideSeconds(3600);

        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 1, 10, 5)),
                new Detection(3, "AA1111", 1003, 90, LocalDateTime.of(2026, 3, 1, 10, 6))
        );

        SequenceRecord record = engine.build(detections, config, LocalDateTime.of(2026, 3, 1, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("drive_in", "backyard", "service");
    }

    @Test
    void singleCameraStageSplitsAfterItsOwnTimeout() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 10, 10)),
                new Detection(3, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 10, 50))
        );

        SequenceRecord record = engine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 1, 11, 0)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(3);
        assertThat(record.stagesChronologically().get(0).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 0));
        assertThat(record.stagesChronologically().get(0).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 0));
        assertThat(record.stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 10));
        assertThat(record.stagesChronologically().get(1).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 10));
        assertThat(record.stagesChronologically().get(2).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 50));
        assertThat(record.stagesChronologically().get(2).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 50));
    }

    @Test
    void sequenceCloseWhileSingleCameraIsStillActiveLeavesOutEmpty() {
        AppConfig config = TestConfigFactory.config();
        config.setSequenceCloseTimeoutMinutes(5);
        config.getSingleCameraStages().getFirst().setTimeoutSeconds(3600);

        SequenceRecord record = engine.build(
                List.of(new Detection(1, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 10, 0))),
                config,
                LocalDateTime.of(2026, 3, 1, 10, 20))
                .getFirst();

        assertThat(record.isClosed()).isTrue();
        assertThat(record.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 5));
        assertThat(record.stagesChronologically()).hasSize(1);
        assertThat(record.stagesChronologically().getFirst().timeOut()).isNull();
    }
}
