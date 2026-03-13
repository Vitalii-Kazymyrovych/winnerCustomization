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
                new Detection(5, "A123", 21, 90, t.plusMinutes(40)),
                new Detection(6, "A123", 13, 90, t.plusMinutes(50)),
                new Detection(7, "A123", 14, 90, t.plusMinutes(52)),
                new Detection(8, "A123", 15, 90, t.plusMinutes(70))
        );

        List<SequenceRecord> result = engine.build(detections, config);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getFinishedAt()).isEqualTo(t.plusMinutes(70));
        assertThat(result.getFirst().getPath()).contains("Parking (out)");
    }

    @Test
    void shouldIgnoreDirectionOutsideRange() {
        AppConfig config = baseConfig();
        config.getCameras().getDriveInIn().getDirectionRange().setFrom(0);
        config.getCameras().getDriveInIn().getDirectionRange().setTo(180);

        List<SequenceRecord> result = engine.build(List.of(
                new Detection(1, "A123", 10, 270, LocalDateTime.now())
        ), config);

        assertThat(result).isEmpty();
    }

    private AppConfig baseConfig() {
        AppConfig c = new AppConfig();
        c.setTiming(new AppConfig.TimingConfig());
        AppConfig.CamerasConfig cameras = new AppConfig.CamerasConfig();
        cameras.setDriveInIn(camera(10));
        cameras.setDriveInOut(camera(11));
        cameras.setServiceIn(camera(12));
        cameras.setServiceOut(camera(13));
        cameras.setParkingIn(camera(14));
        cameras.setParkingOut(camera(15));
        AppConfig.PostCameraConfig post = new AppConfig.PostCameraConfig();
        post.setPostName("post-1");
        post.setIn(camera(20));
        post.setOut(camera(21));
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
}
