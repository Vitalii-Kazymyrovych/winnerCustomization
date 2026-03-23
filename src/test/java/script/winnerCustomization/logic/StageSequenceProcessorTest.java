package script.winnerCustomization.logic;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.TestConfigFactory;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageSequenceProcessorTest {
    private final StageSequenceProcessor processor = new StageSequenceProcessor();

    @Test
    void transitionCandidateFromStageEndMaterializesOnlyAfterTimeout() {
        var config = TestConfigFactory.standardConfig();
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 10, 0);

        var result = processor.process(List.of(
                detection(1, "AA1111", 1003, null, base),
                detection(2, "AA1111", 1004, null, base.plusSeconds(4))
        ), config, base.plusSeconds(20));

        var stages = result.sequences().getFirst().stagesChronologically();
        assertThat(stages).extracting(SequenceRecord.StageWindow::reportLabel)
                .containsExactly("Service", "Backyard");
        assertThat(stages.get(0).timeIn()).isEqualTo(base);
        assertThat(stages.get(0).timeOut()).isEqualTo(base.plusSeconds(4));
        assertThat(stages.get(1).timeIn()).isEqualTo(base.plusSeconds(5));
        assertThat(stages.get(1).timeOut()).isNull();
    }

    @Test
    void transitionCandidateIsCanceledWhenAnotherStageStartsBeforeTimeout() {
        var config = TestConfigFactory.standardConfig();
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 10, 30);

        var result = processor.process(List.of(
                detection(1, "BB2222", 1003, null, base),
                detection(2, "BB2222", 1004, null, base.plusSeconds(4)),
                detection(3, "BB2222", 1001, 90, base.plusSeconds(10))
        ), config, base.plusMinutes(5));

        var stages = result.sequences().getFirst().stagesChronologically();
        assertThat(stages).extracting(SequenceRecord.StageWindow::reportLabel)
                .containsExactly("Service", "Drive In");
        assertThat(stages.get(0).timeOut()).isEqualTo(base.plusSeconds(4));
        assertThat(stages.get(1).timeIn()).isEqualTo(base.plusSeconds(10));
    }

    @Test
    void realStageUsesStickyOutAndReopensOnlyAfterOut() {
        var config = TestConfigFactory.standardConfig();
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 11, 0);

        var result = processor.process(List.of(
                detection(1, "CC3333", 1001, 90, base),
                detection(2, "CC3333", 1001, 100, base.plusSeconds(2)),
                detection(3, "CC3333", 1002, 250, base.plusSeconds(10)),
                detection(4, "CC3333", 1002, 260, base.plusSeconds(12)),
                detection(5, "CC3333", 1001, 90, base.plusSeconds(20))
        ), config, base.plusMinutes(5));

        var stages = result.sequences().getFirst().stagesChronologically();
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).timeIn()).isEqualTo(base);
        assertThat(stages.get(0).timeOut()).isEqualTo(base.plusSeconds(12));
        assertThat(stages.get(1).timeIn()).isEqualTo(base.plusSeconds(20));
        assertThat(stages.get(1).timeOut()).isNull();
    }

    @Test
    void partialRealOutClosesActiveTransitionalAtPreviousSecond() {
        var config = TestConfigFactory.standardConfig();
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 11, 30);

        var result = processor.process(List.of(
                detection(1, "DD4444", 1003, null, base),
                detection(2, "DD4444", 1004, null, base.plusSeconds(4)),
                detection(3, "DD4444", 1002, 250, base.plusSeconds(20))
        ), config, base.plusSeconds(22));

        var stages = result.sequences().getFirst().stagesChronologically();
        assertThat(stages).extracting(SequenceRecord.StageWindow::reportLabel)
                .containsExactly("Service", "Backyard", "Drive In (partial)");
        assertThat(stages.get(1).timeIn()).isEqualTo(base.plusSeconds(5));
        assertThat(stages.get(1).timeOut()).isEqualTo(base.plusSeconds(19));
        assertThat(stages.get(2).timeIn()).isNull();
        assertThat(stages.get(2).timeOut()).isEqualTo(base.plusSeconds(20));
    }

    @Test
    void singleStageKeepsCollectingSameCameraDetectionsWithoutStageTimeout() {
        var config = TestConfigFactory.standardConfig();
        config.getTransitionalStages().getFirst().setAllowedAfter(List.of());
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 12, 0);

        var result = processor.process(List.of(
                detection(1, "EE5555", 3001, null, base),
                detection(2, "EE5555", 3001, null, base.plusSeconds(2)),
                detection(3, "EE5555", 3001, null, base.plusSeconds(20))
        ), config, base.plusSeconds(30));

        var stages = result.sequences().getFirst().stagesChronologically();
        assertThat(stages).extracting(SequenceRecord.StageWindow::reportLabel)
                .containsExactly("Post 1");
        assertThat(stages.getFirst().timeIn()).isEqualTo(base);
        assertThat(stages.getFirst().timeOut()).isNull();
    }

    @Test
    void singleStageClosesOnLastPostDetectionWhenAnotherCameraAppears() {
        var config = TestConfigFactory.standardConfig();
        config.getTransitionalStages().getFirst().setAllowedAfter(List.of());
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 12, 30);

        var result = processor.process(List.of(
                detection(1, "EF5656", 3001, null, base),
                detection(2, "EF5656", 3001, null, base.plusSeconds(2)),
                detection(3, "EF5656", 3001, null, base.plusSeconds(20)),
                detection(4, "EF5656", 1003, null, base.plusSeconds(25))
        ), config, base.plusMinutes(1));

        var stages = result.sequences().getFirst().stagesChronologically();
        assertThat(stages).extracting(SequenceRecord.StageWindow::reportLabel)
                .containsExactly("Post 1", "Service");
        assertThat(stages.get(0).timeIn()).isEqualTo(base);
        assertThat(stages.get(0).timeOut()).isEqualTo(base.plusSeconds(20));
        assertThat(stages.get(1).timeIn()).isEqualTo(base.plusSeconds(25));
        assertThat(stages.get(1).timeOut()).isNull();
    }

    @Test
    void sequenceCloseKeepsIncompleteRealButDropsIncompleteTransitionalWhenConfigured() {
        var config = TestConfigFactory.standardConfig();
        config.setSequenceCloseTimeoutMinutes(1);
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 13, 0);

        var result = processor.process(List.of(
                detection(1, "FF6666", 1001, 90, base),
                detection(2, "GG7777", 1003, null, base),
                detection(3, "GG7777", 1004, null, base.plusSeconds(1))
        ), config, base.plusMinutes(2));

        var realSequence = result.sequences().stream().filter(it -> it.getPlateNumber().equals("FF6666")).findFirst().orElseThrow();
        assertThat(realSequence.stagesChronologically()).singleElement().satisfies(stage -> assertThat(stage.timeOut()).isNull());

        var transitionalSequence = result.sequences().stream().filter(it -> it.getPlateNumber().equals("GG7777")).findFirst().orElseThrow();
        assertThat(transitionalSequence.stagesChronologically()).extracting(SequenceRecord.StageWindow::reportLabel)
                .containsExactly("Service");
    }

    private Detection detection(long id, String plate, int camera, Integer direction, LocalDateTime timestamp) {
        return new Detection(id, plate, camera, direction, timestamp);
    }
}
