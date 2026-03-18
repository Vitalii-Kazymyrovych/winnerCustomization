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
    void shouldBuildSingleFullSequence() {
        AppConfig config = baseConfig();
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 10, 0);
        List<Detection> detections = List.of(
                new Detection(1, "A123", 10, 90, t),
                new Detection(2, "A123", 11, 90, t.plusMinutes(5)),
                new Detection(3, "A123", 12, 90, t.plusMinutes(8)),
                new Detection(4, "A123", 20, 90, t.plusMinutes(12)),
                new Detection(5, "A123", 20, 250, t.plusMinutes(20)),
                new Detection(6, "A123", 13, 90, t.plusMinutes(50)),
                new Detection(7, "A123", 16, 90, t.plusMinutes(51)),
                new Detection(8, "A123", 10, 90, t.plusMinutes(52)),
                new Detection(9, "A123", 14, 90, t.plusMinutes(55)),
                new Detection(10, "A123", 15, 90, t.plusMinutes(70))
        );

        List<SequenceRecord> result = engine.build(detections, config);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getFinishedAt()).isEqualTo(t.plusMinutes(70));
        assertThat(result.getFirst().getPostOutAt()).isEqualTo(t.plusMinutes(20));
        assertThat(result.getFirst().getServiceToDriveInAt()).isEqualTo(t.plusMinutes(51));
        assertThat(result.getFirst().getPath()).contains("Parking (out)");
    }

    @Test
    void shouldCreateBackyardAfterDriveInToServiceWhenServiceInMissing() {
        AppConfig config = baseConfig();
        LocalDateTime t = LocalDateTime.of(2026, 1, 2, 10, 0);

        List<SequenceRecord> result = engine.build(List.of(
                new Detection(1, "B234", 10, 90, t),
                new Detection(2, "B234", 11, 90, t.plusMinutes(5)),
                new Detection(3, "B234", 17, 90, t.plusMinutes(7)),
                new Detection(4, "B234", 14, 90, t.plusMinutes(10))
        ), config);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getBackyardStages()).singleElement().satisfies(stage -> {
            assertThat(stage.timeIn()).isEqualTo(t.plusMinutes(7));
            assertThat(stage.timeOut()).isEqualTo(t.plusMinutes(10));
        });
    }

    @Test
    void shouldCreateBackyardAfterServiceOutWhenServiceToDriveInMissing() {
        AppConfig config = baseConfig();
        LocalDateTime t = LocalDateTime.of(2026, 1, 3, 10, 0);

        List<SequenceRecord> result = engine.build(List.of(
                new Detection(1, "C345", 10, 90, t),
                new Detection(2, "C345", 11, 90, t.plusMinutes(5)),
                new Detection(3, "C345", 12, 90, t.plusMinutes(8)),
                new Detection(4, "C345", 13, 90, t.plusMinutes(30)),
                new Detection(5, "C345", 14, 90, t.plusMinutes(45))
        ), config);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getBackyardStages()).singleElement().satisfies(stage -> {
            assertThat(stage.timeIn()).isEqualTo(t.plusMinutes(30));
            assertThat(stage.timeOut()).isEqualTo(t.plusMinutes(45));
        });
    }

    @Test
    void shouldKeepTestDriveAnchorAfterServiceToDriveInWithoutDriveInIn() {
        AppConfig config = baseConfig();
        LocalDateTime t = LocalDateTime.of(2026, 1, 4, 10, 0);

        List<SequenceRecord> result = engine.build(List.of(
                new Detection(1, "D456", 10, 90, t),
                new Detection(2, "D456", 11, 90, t.plusMinutes(5)),
                new Detection(3, "D456", 12, 90, t.plusMinutes(8)),
                new Detection(4, "D456", 13, 90, t.plusMinutes(30)),
                new Detection(5, "D456", 16, 90, t.plusMinutes(31)),
                new Detection(6, "D456", 99, 90, t.plusMinutes(32))
        ), config);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getTestDriveAnchorAt()).isEqualTo(t.plusMinutes(31));
    }

    @Test
    void shouldIgnoreDirectionOutsideRange() {
        AppConfig config = baseConfig();
        config.getCameras().getDriveInIn().getFirst().getDirectionRange().setFrom(0);
        config.getCameras().getDriveInIn().getFirst().getDirectionRange().setTo(180);

        List<SequenceRecord> result = engine.build(List.of(
                new Detection(1, "A123", 10, 270, LocalDateTime.now())
        ), config);

        assertThat(result).isEmpty();
    }

    private AppConfig baseConfig() {
        AppConfig c = new AppConfig();
        c.setTiming(new AppConfig.TimingConfig());
        AppConfig.CamerasConfig cameras = new AppConfig.CamerasConfig();
        cameras.setDriveInIn(List.of(camera(10)));
        cameras.setDriveInOut(List.of(camera(11)));
        cameras.setDriveInToService(List.of(camera(17)));
        cameras.setServiceIn(List.of(camera(12)));
        cameras.setServiceOut(List.of(camera(13)));
        cameras.setServiceToDriveIn(List.of(camera(16)));
        cameras.setParkingIn(List.of(camera(14)));
        cameras.setParkingOut(List.of(camera(15)));

        AppConfig.PostCameraConfig post = new AppConfig.PostCameraConfig();
        post.setPostName("post-1");
        post.setAnalyticsId(20);
        post.setInDirectionRange(range(0, 180));
        post.setOutDirectionRange(range(181, 360));
        cameras.setServicePosts(List.of(post));

        c.setCameras(cameras);
        return c;
    }

    private AppConfig.CameraConfig camera(int id) {
        AppConfig.CameraConfig camera = new AppConfig.CameraConfig();
        camera.setAnalyticsId(id);
        camera.setDirectionRange(new AppConfig.DirectionRange());
        return camera;
    }

    private AppConfig.DirectionRange range(int from, int to) {
        AppConfig.DirectionRange range = new AppConfig.DirectionRange();
        range.setFrom(from);
        range.setTo(to);
        return range;
    }
}
