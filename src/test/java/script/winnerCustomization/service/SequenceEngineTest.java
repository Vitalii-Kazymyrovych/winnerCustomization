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

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config()).getFirst();

        assertThat(record.stagesChronologically()).hasSize(3);
        assertThat(record.stagesChronologically().get(0).stageName()).isEqualTo("drive_in");
        assertThat(record.stagesChronologically().get(0).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 10));
        assertThat(record.stagesChronologically().get(1).partial()).isTrue();
        assertThat(record.stagesChronologically().get(1).timeIn()).isNull();
        assertThat(record.stagesChronologically().get(2).stageName()).isEqualTo("service");
    }

    @Test
    void materializesTransitionalStageAfterTimeout() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1001, 10, LocalDateTime.of(2026, 3, 1, 10, 0)),
                new Detection(2, "AA1111", 1008, null, LocalDateTime.of(2026, 3, 1, 10, 5)),
                new Detection(3, "AA1111", 1003, 90, LocalDateTime.of(2026, 3, 1, 10, 8))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config()).getFirst();

        assertThat(record.stagesChronologically()).extracting(SequenceRecord.StageWindow::stageName)
                .containsExactly("drive_in", "backyard", "service");
        assertThat(record.stagesChronologically().get(1).stageType()).isEqualTo(SequenceRecord.StageType.TRANSITIONAL);
        assertThat(record.stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 5));
        assertThat(record.stagesChronologically().get(1).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 10, 8));
    }

    @Test
    void splitsSingleCameraStageWhenGapExceedsTimeout() {
        List<Detection> detections = List.of(
                new Detection(1, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 9, 0)),
                new Detection(2, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 9, 0, 10)),
                new Detection(3, "AA1111", 1101, null, LocalDateTime.of(2026, 3, 1, 9, 1))
        );

        SequenceRecord record = sequenceEngine.build(detections, TestConfigFactory.config()).getFirst();

        assertThat(record.stagesChronologically()).hasSize(2);
        assertThat(record.stagesChronologically().get(0).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 0));
        assertThat(record.stagesChronologically().get(0).timeOut()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 0, 10));
        assertThat(record.stagesChronologically().get(1).timeIn()).isEqualTo(LocalDateTime.of(2026, 3, 1, 9, 1));
    }
}
