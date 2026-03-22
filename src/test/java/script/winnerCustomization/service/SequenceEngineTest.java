package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceEngineTest {
    private final SequenceEngine sequenceEngine = new SequenceEngine();

    @Test
    void buildsRealStagesAndCreatesPartialOutForAnotherStage() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1002, 200, LocalDateTime.of(2026, 3, 1, 10, 10)),
                new Detection(3, "AA1111", 1005, 10, LocalDateTime.of(2026, 3, 1, 10, 15)),
                new Detection(4, "AA1111", 1003, 90, LocalDateTime.of(2026, 3, 1, 10, 20))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 1, 12, 0)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(3);
        assertThat(record.stagesChronologically().get(0).stageName()).isEqualTo("drive_in");
        assertThat(record.stagesChronologically().get(0).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 10));
        assertThat(record.stagesChronologically().get(1).partial()).isTrue();
        assertThat(record.stagesChronologically().get(1).timeIn()).isNull();
        assertThat(record.stagesChronologically().get(2).stageName()).isEqualTo("service");
        assertThat(record.isClosed()).isFalse();
    }

    @Test
    void materializesTransitionalStageAfterTimeoutWithoutDuplicatingSameBackyard() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 1, 10, 5)),
                new Detection(3, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 1, 10, 6)),
                new Detection(4, "AA1111", 1003, 90, LocalDateTime.of(2026, 3, 1, 10, 9))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 1, 12, 0)).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("drive_in", "backyard", "service");
        assertThat(record.stagesChronologically().get(1).stageType()).isEqualTo(SequenceRecord.StageType.TRANSITIONAL);
        assertThat(record.stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 5));
        assertThat(record.stagesChronologically().get(1).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 9));
    }

    @Test
    void keepsSingleCameraStageOpenUntilReportTimeAndAggregatesRepeatedDetections() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 9, 0)),
                new Detection(2, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 9, 0, 10)),
                new Detection(3, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 9, 0, 20))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 1, 9, 0, 40)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(1);
        assertThat(record.stagesChronologically().getFirst().timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 0));
        assertThat(record.stagesChronologically().getFirst().timeOut()).isNull();
        assertThat(record.stagesChronologically().getFirst().lastSeenAt()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 0, 20));
        assertThat(record.isClosed()).isFalse();
    }

    @Test
    void keepsSingleCameraStageStickyAcrossLongGapsUntilRealOutArrives() {
        List<Detection> detections = List.of(
                new Detection(1, "AA4444PO", 1003, 90, LocalDateTime.of(2026, 3, 18, 16, 4, 13)),
                new Detection(2, "AA4444PO", 1101, null, LocalDateTime.of(2026, 3, 18, 16, 4, 37)),
                new Detection(3, "AA4444PO", 1101, null, LocalDateTime.of(2026, 3, 18, 16, 21, 7)),
                new Detection(4, "AA4444PO", 1101, null, LocalDateTime.of(2026, 3, 18, 17, 15, 48)),
                new Detection(5, "AA4444PO", 1005, 10, LocalDateTime.of(2026, 3, 18, 17, 17, 0))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 18, 18, 0)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(2);
        assertThat(record.stagesChronologically().get(0).stageName()).isEqualTo("service");
        assertThat(record.stagesChronologically().get(0).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 18, 16, 4, 37));
        assertThat(record.stagesChronologically().get(1).stageName()).isEqualTo("post_1");
        assertThat(record.stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 18, 16, 4, 37));
        assertThat(record.stagesChronologically().get(1).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 18, 17, 17, 0));
        assertThat(record.stagesChronologically().get(1).partial()).isFalse();
    }

    @Test
    void closesSingleCameraStageAtLastSeenWhenOnlyPostDetectionsExist() {
        List<Detection> detections = List.of(
                new Detection(1, "KA1163K", 1101, null, LocalDateTime.of(2026, 3, 18, 14, 34, 35)),
                new Detection(2, "KA1163K", 1101, null, LocalDateTime.of(2026, 3, 18, 14, 40, 4)),
                new Detection(3, "KA1163K", 1101, null, LocalDateTime.of(2026, 3, 18, 14, 43, 26))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 18, 15, 30)).getFirst();

        assertThat(record.stagesChronologically()).hasSize(1);
        assertThat(record.stagesChronologically().getFirst().stageName()).isEqualTo("post_1");
        assertThat(record.stagesChronologically().getFirst().timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 18, 14, 34, 35));
        assertThat(record.stagesChronologically().getFirst().timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 18, 14, 43, 26));
        assertThat(record.isClosed()).isTrue();
    }
    @Test
    void closesSingleCameraStageAtLastSeenWhenSequenceTimeoutStartsNewSequence() {
        List<Detection> detections = List.of(
                new Detection(1, "1163KK", 1101, null, LocalDateTime.of(2026, 3, 18, 15, 55, 7)),
                new Detection(2, "1163KK", 1101, null, LocalDateTime.of(2026, 3, 18, 15, 56, 7)),
                new Detection(3, "1163KK", 1101, null, LocalDateTime.of(2026, 3, 18, 17, 30, 0))
        );

        List<SequenceRecord> records = sequenceEngine.build(detections, TestConfigFactory.config(), LocalDateTime.of(2026, 3, 18, 18, 0));

        assertThat(records).hasSize(2);
        assertThat(records.get(0).stagesChronologically()).hasSize(1);
        assertThat(records.get(0).stagesChronologically().getFirst().timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 18, 15, 56, 7));
        assertThat(records.get(0).isClosed()).isTrue();
        assertThat(records.get(1).stagesChronologically()).hasSize(1);
        assertThat(records.get(1).stagesChronologically().getFirst().timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 18, 17, 30, 0));
    }

}
