package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageType;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SequenceEngineTest {
    private final SequenceEngine engine = new SequenceEngine();

    @Test
    void shouldBuildDriveInStageWithoutAlertWhenClosedInTime() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 10, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1111", 10, 90, t),
                detection(2, "AA1111", 11, 90, t.plusMinutes(5))
        )).getFirst();

        assertStages(record, tuple("Drive In", t, t.plusMinutes(5), ""));
    }

    @Test
    void shouldCreateDriveInAlertWhenRecoveredByNextStageAfterThreshold() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 10, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1112", 10, 90, t),
                detection(2, "AA1112", 12, 90, t.plusMinutes(20))
        )).getFirst();

        assertStages(record,
                tuple("Drive In", t, t.plusMinutes(20).minusSeconds(1), "No Drive in (out) within 15 minutes"),
                tuple("Service", t.plusMinutes(20), null, ""));
    }

    @Test
    void shouldCreateServiceAlertWhenNoPostInWithinThreshold() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 11, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1113", 12, 90, t),
                detection(2, "AA1113", 13, 90, t.plusMinutes(20))
        )).getFirst();

        assertStages(record,
                tuple("Service", t, t.plusMinutes(20).minusSeconds(1), "No Post in within 15 minutes"),
                tuple("Backyard", t.plusMinutes(20), null, ""));
    }

    @Test
    void shouldBuildServicePostServiceChain() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 12, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1114", 12, 90, t),
                detection(2, "AA1114", 20, 90, t.plusMinutes(5)),
                detection(3, "AA1114", 20, 250, t.plusMinutes(15)),
                detection(4, "AA1114", 13, 90, t.plusMinutes(30))
        )).getFirst();

        assertStages(record,
                tuple("Service", t, t.plusMinutes(5).minusSeconds(1), ""),
                tuple("Post", t.plusMinutes(5), t.plusMinutes(15), ""),
                tuple("Service", t.plusMinutes(15), t.plusMinutes(30).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(30), null, ""));
    }

    @Test
    void shouldSupportSeveralPostsInSameSequence() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 13, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1115", 12, 90, t),
                detection(2, "AA1115", 20, 90, t.plusMinutes(3)),
                detection(3, "AA1115", 20, 250, t.plusMinutes(7)),
                detection(4, "AA1115", 20, 90, t.plusMinutes(10)),
                detection(5, "AA1115", 20, 250, t.plusMinutes(14)),
                detection(6, "AA1115", 13, 90, t.plusMinutes(18))
        )).getFirst();

        assertStages(record,
                tuple("Service", t, t.plusMinutes(3).minusSeconds(1), ""),
                tuple("Post", t.plusMinutes(3), t.plusMinutes(7), ""),
                tuple("Service", t.plusMinutes(7), t.plusMinutes(10).minusSeconds(1), ""),
                tuple("Post", t.plusMinutes(10), t.plusMinutes(14), ""),
                tuple("Service", t.plusMinutes(14), t.plusMinutes(18).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(18), null, ""));
    }

    @Test
    void shouldBuildParkingAndBackyardAfterParkingOut() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 14, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1116", 14, 90, t),
                detection(2, "AA1116", 15, 90, t.plusMinutes(8)),
                detection(3, "AA1116", 10, 90, t.plusMinutes(15))
        )).getFirst();

        assertStages(record,
                tuple("Parking", t, t.plusMinutes(8).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(8), t.plusMinutes(15).minusSeconds(1), ""),
                tuple("Drive In", t.plusMinutes(15), null, ""));
    }

    @Test
    void shouldBuildBackyardForDriveInToServiceAndServiceOutTriggers() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 15, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "AA1117", 10, 90, t),
                detection(2, "AA1117", 17, 90, t.plusMinutes(2)),
                detection(3, "AA1117", 14, 90, t.plusMinutes(5)),
                detection(4, "AA1117", 15, 90, t.plusMinutes(7)),
                detection(5, "AA1117", 12, 90, t.plusMinutes(12)),
                detection(6, "AA1117", 13, 90, t.plusMinutes(18)),
                detection(7, "AA1117", 10, 90, t.plusMinutes(22))
        )).getFirst();

        assertStages(record,
                tuple("Drive In", t, t.plusMinutes(2).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(2), t.plusMinutes(5).minusSeconds(1), ""),
                tuple("Parking", t.plusMinutes(5), t.plusMinutes(7).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(7), t.plusMinutes(12).minusSeconds(1), ""),
                tuple("Service", t.plusMinutes(12), t.plusMinutes(18).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(18), t.plusMinutes(22).minusSeconds(1), ""),
                tuple("Drive In", t.plusMinutes(22), null, ""));
    }

    @Test
    void shouldCreateTestDriveOnlyWhenGapReachesWindowAndReturnBeforeTimeout() {
        AppConfig config = baseConfig();
        config.getTiming().setTestDriveStartMinutes(10);
        config.getTiming().setTestDriveResetMinutes(60);
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 16, 0);

        SequenceRecord record = build(config, List.of(
                detection(1, "AA1118", 10, 90, t),
                detection(2, "AA1118", 11, 90, t.plusMinutes(3)),
                detection(3, "AA1118", 14, 90, t.plusMinutes(20))
        )).getFirst();

        assertStages(record,
                tuple("Drive In", t, t.plusMinutes(3), ""),
                tuple("Test-Drive", t.plusMinutes(3), t.plusMinutes(20).minusSeconds(1), ""),
                tuple("Parking", t.plusMinutes(20), null, ""));
    }

    @Test
    void shouldCloseServiceBeforeStartingTestDriveFromServiceToDriveIn() {
        AppConfig config = baseConfig();
        config.getTiming().setTestDriveStartMinutes(5);
        config.getTiming().setTestDriveResetMinutes(60);
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 16, 30);

        SequenceRecord record = build(config, List.of(
                detection(1, "AA1118A", 12, 90, t),
                detection(2, "AA1118A", 16, 90, t.plusMinutes(7)),
                detection(3, "AA1118A", 17, 90, t.plusMinutes(20))
        )).getFirst();

        assertStages(record,
                tuple("Service", t, t.plusMinutes(7).minusSeconds(1), ""),
                tuple("Test-Drive", t.plusMinutes(7), t.plusMinutes(20).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(20), null, ""));
    }

    @Test
    void shouldNotCreateTestDriveWhenNextEventArrivesTooSoon() {
        AppConfig config = baseConfig();
        config.getTiming().setTestDriveStartMinutes(10);
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 17, 0);

        SequenceRecord record = build(config, List.of(
                detection(1, "AA1119", 10, 90, t),
                detection(2, "AA1119", 11, 90, t.plusMinutes(3)),
                detection(3, "AA1119", 14, 90, t.plusMinutes(8))
        )).getFirst();

        assertStages(record,
                tuple("Drive In", t, t.plusMinutes(3), ""),
                tuple("Parking", t.plusMinutes(8), null, ""));
    }

    @Test
    void shouldDropTestDriveAndCloseSequenceOnTimeout() {
        AppConfig config = baseConfig();
        config.getTiming().setTestDriveStartMinutes(10);
        config.getTiming().setTestDriveResetMinutes(60);
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 18, 0);

        List<SequenceRecord> records = build(config, List.of(
                detection(1, "AA1120", 10, 90, t),
                detection(2, "AA1120", 11, 90, t.plusMinutes(3)),
                detection(3, "AA1120", 10, 90, t.plusMinutes(70))
        ));

        assertThat(records).hasSize(2);
        assertStages(records.get(0), tuple("Drive In", t, t.plusMinutes(3), ""));
        assertStages(records.get(1), tuple("Drive In", t.plusMinutes(70), null, ""));
    }

    @Test
    void shouldDropTransitionOnlySequenceWithoutConcreteStages() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 18, 30);

        List<SequenceRecord> records = build(baseConfig(), List.of(
                detection(1, "AA1120A", 16, 90, t)
        ));

        assertThat(records).isEmpty();
    }

    @Test
    void shouldApplyRecoveryForExitOnlyEvents() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 19, 0);

        SequenceRecord serviceOut = build(baseConfig(), List.of(
                detection(1, "BB1001", 13, 90, t)
        )).getFirst();
        assertStages(serviceOut,
                tuple("Service", null, t, ""),
                tuple("Backyard", t, null, ""));

        SequenceRecord parkingOut = build(baseConfig(), List.of(
                detection(1, "BB1002", 15, 90, t)
        )).getFirst();
        assertStages(parkingOut,
                tuple("Parking", null, t, ""),
                tuple("Backyard", t, null, ""));

        SequenceRecord driveInOut = build(baseConfig(), List.of(
                detection(1, "BB1003", 11, 90, t)
        )).getFirst();
        assertStages(driveInOut, tuple("Drive In", null, t, ""));

        SequenceRecord postOut = build(baseConfig(), List.of(
                detection(1, "BB1004", 20, 250, t)
        )).getFirst();
        assertStages(postOut,
                tuple("Post", null, t, ""),
                tuple("Service", t, null, ""));
    }

    @Test
    void shouldCreateRecoveryServiceBeforePostInWithoutActiveService() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 20, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "CC1001", 20, 90, t)
        )).getFirst();

        assertStages(record,
                tuple("Service", null, t.minusSeconds(1), ""),
                tuple("Post", t, null, ""));
    }

    @Test
    void shouldIgnoreRepeatedStageStartsAndBackyardTriggers() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 21, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "CC1002", 10, 90, t),
                detection(2, "CC1002", 10, 90, t.plusMinutes(1)),
                detection(3, "CC1002", 17, 90, t.plusMinutes(2)),
                detection(4, "CC1002", 17, 90, t.plusMinutes(3)),
                detection(5, "CC1002", 12, 90, t.plusMinutes(5))
        )).getFirst();

        assertStages(record,
                tuple("Drive In", t, t.plusMinutes(2).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(2), t.plusMinutes(5).minusSeconds(1), ""),
                tuple("Service", t.plusMinutes(5), null, ""));
    }

    @Test
    void shouldNormalizeEqualTimestampsPerPlate() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 22, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "CC1003", 10, 90, t),
                detection(2, "CC1003", 11, 90, t),
                detection(3, "CC1003", 14, 90, t)
        )).getFirst();

        assertStages(record,
                tuple("Drive In", t, t.plusSeconds(1), ""),
                tuple("Parking", t.plusSeconds(2), null, ""));
    }

    @Test
    void shouldOverwritePostOutAndShiftFollowingServiceStart() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 23, 0);

        SequenceRecord record = build(baseConfig(), List.of(
                detection(1, "CC1004", 12, 90, t),
                detection(2, "CC1004", 20, 90, t.plusMinutes(5)),
                detection(3, "CC1004", 20, 250, t.plusMinutes(8)),
                detection(4, "CC1004", 20, 250, t.plusMinutes(10)),
                detection(5, "CC1004", 13, 90, t.plusMinutes(20))
        )).getFirst();

        assertStages(record,
                tuple("Service", t, t.plusMinutes(5).minusSeconds(1), ""),
                tuple("Post", t.plusMinutes(5), t.plusMinutes(10), ""),
                tuple("Service", t.plusMinutes(10), t.plusMinutes(20).minusSeconds(1), ""),
                tuple("Backyard", t.plusMinutes(20), null, ""));
    }

    @Test
    void shouldStartNewSequenceAfterFortyEightHourGap() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 18, 9, 0);

        List<SequenceRecord> records = build(baseConfig(), List.of(
                detection(1, "CC1005", 10, 90, t),
                detection(2, "CC1005", 11, 90, t.plusMinutes(2)),
                detection(3, "CC1005", 10, 90, t.plusHours(49))
        ));

        assertThat(records).hasSize(2);
        assertStages(records.get(0), tuple("Drive In", t, t.plusMinutes(2), ""));
        assertStages(records.get(1), tuple("Drive In", t.plusHours(49), null, ""));
    }

    @Test
    void shouldIgnoreDirectionOutsideConfiguredRange() {
        AppConfig config = baseConfig();
        config.getCameras().getDriveInIn().getFirst().getDirectionRange().setFrom(0);
        config.getCameras().getDriveInIn().getFirst().getDirectionRange().setTo(180);

        List<SequenceRecord> result = build(config, List.of(
                detection(1, "DD1001", 10, 270, LocalDateTime.of(2026, 3, 18, 12, 0))
        ));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldSupportWrappedDirectionRangesWithoutBoundaryOverlap() {
        AppConfig config = baseConfig();
        config.getCameras().setDriveInIn(List.of());
        config.getCameras().setDriveInOut(List.of());
        config.getCameras().setServiceIn(List.of());
        config.getCameras().setServiceOut(List.of());
        config.getCameras().setDriveInToService(List.of());
        config.getCameras().setServiceToDriveIn(List.of());
        config.getCameras().setParkingIn(List.of(camera(14, 270, 90)));
        config.getCameras().setParkingOut(List.of(camera(15, 90, 270)));

        List<SequenceRecord> records = build(config, List.of(
                detection(1, "DD1002", 14, 0, LocalDateTime.of(2026, 3, 18, 12, 0)),
                detection(2, "DD1003", 15, 90, LocalDateTime.of(2026, 3, 18, 12, 5)),
                detection(3, "DD1004", 14, 270, LocalDateTime.of(2026, 3, 18, 12, 10))
        ));

        assertThat(records).hasSize(3);
        assertStages(records.get(0), tuple("Parking", LocalDateTime.of(2026, 3, 18, 12, 0), null, ""));
        assertStages(records.get(1),
                tuple("Parking", null, LocalDateTime.of(2026, 3, 18, 12, 5), ""),
                tuple("Backyard", LocalDateTime.of(2026, 3, 18, 12, 5), null, ""));
        assertStages(records.get(2), tuple("Parking", LocalDateTime.of(2026, 3, 18, 12, 10), null, ""));
    }

    private List<SequenceRecord> build(AppConfig config, List<Detection> detections) {
        return engine.build(detections, config);
    }

    private void assertStages(SequenceRecord record, org.assertj.core.groups.Tuple... expectedTuples) {
        assertThat(record.stagesChronologically())
                .extracting(stage -> stage.reportLabel(), StageWindow::timeIn, StageWindow::timeOut, StageWindow::alert)
                .containsExactly(expectedTuples);
    }

    private Detection detection(long id, String plate, int cameraId, int direction, LocalDateTime at) {
        return new Detection(id, plate, cameraId, direction, at);
    }

    private AppConfig baseConfig() {
        AppConfig c = new AppConfig();
        AppConfig.TimingConfig timing = new AppConfig.TimingConfig();
        timing.setDriveInToDriveOutAlertMinutes(15);
        timing.setServiceToPostAlertMinutes(15);
        timing.setTestDriveStartMinutes(30);
        timing.setTestDriveResetMinutes(60);
        c.setTiming(timing);

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

    private AppConfig.CameraConfig camera(int id, int from, int to) {
        AppConfig.CameraConfig camera = camera(id);
        camera.setDirectionRange(range(from, to));
        return camera;
    }

    private AppConfig.DirectionRange range(int from, int to) {
        AppConfig.DirectionRange range = new AppConfig.DirectionRange();
        range.setFrom(from);
        range.setTo(to);
        return range;
    }
}
