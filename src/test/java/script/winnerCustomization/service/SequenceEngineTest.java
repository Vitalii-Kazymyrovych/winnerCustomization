package script.winnerCustomization.service;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SequenceEngineTest {
    private final SequenceEngine engine = new SequenceEngine();
    private final WorkflowDefaultsFactory workflowDefaultsFactory = new WorkflowDefaultsFactory();

    @Test
    void shouldUseDynamicWorkflowStageNamesFromConfig() {
        AppConfig config = enrich(baseConfigWithLegacyCameras());
        LocalDateTime t = LocalDateTime.of(2026, 3, 20, 10, 0);

        SequenceRecord record = engine.build(List.of(
                detection(1, "AA1111", 12, 90, t),
                detection(2, "AA1111", 20, 90, t.plusMinutes(5)),
                detection(3, "AA1111", 20, 250, t.plusMinutes(8)),
                detection(4, "AA1111", 13, 90, t.plusMinutes(20))
        ), config).getFirst();

        assertThat(record.stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut, StageWindow::partial)
                .containsExactly(
                        tuple("service", "Service", t, t.plusMinutes(5).minusSeconds(1), false),
                        tuple("post_post_1", "Post 1", t.plusMinutes(5), null, false),
                        tuple("service", "Service", null, t.plusMinutes(20), true)
                );
    }

    @Test
    void shouldSupportAllowedNextStagesAndIgnoreUnexpectedEvents() {
        AppConfig config = dynamicWorkflowConfig();
        AppConfig.StageConfig service = stage(config, "service_primary");
        service.setAllowedNextStages(List.of("post_3"));
        service.setUnexpectedNextStagePolicy("ignore");

        LocalDateTime t = LocalDateTime.of(2026, 3, 20, 11, 0);
        SequenceRecord record = engine.build(List.of(
                detection(1, "AA2222", 100, 10, t),
                detection(2, "AA2222", 400, 10, t.plusMinutes(4)),
                detection(3, "AA2222", 200, 10, t.plusMinutes(6))
        ), config).getFirst();

        assertThat(record.stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut)
                .containsExactly(
                        tuple("service_primary", "Service Primary", t, t.plusMinutes(6).minusSeconds(1)),
                        tuple("post_3", "Post 3", t.plusMinutes(6), null)
                );
    }

    @Test
    void shouldMaterializeCandidateAndCancelItOnConfiguredEvent() {
        AppConfig config = dynamicWorkflowConfig();
        LocalDateTime t = LocalDateTime.of(2026, 3, 20, 12, 0);

        List<SequenceRecord> records = engine.build(List.of(
                detection(1, "AA3333", 500, 10, t),
                detection(2, "AA3333", 100, 10, t.plusMinutes(5)),
                detection(3, "AA3333", 500, 10, t.plusMinutes(20)),
                detection(4, "AA3333", 200, 10, t.plusMinutes(40))
        ), config);

        SequenceRecord record = records.getFirst();
        assertThat(record.stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::timeIn, StageWindow::timeOut)
                .containsExactly(
                        tuple("service_primary", t.plusMinutes(5), t.plusMinutes(20).minusSeconds(1)),
                        tuple("test_drive_candidate", t.plusMinutes(20), t.plusMinutes(40).minusSeconds(1)),
                        tuple("post_3", t.plusMinutes(40), null)
                );
    }

    @Test
    void shouldHandleStickyTimeoutTransitionAndGeneralPartialFromFinish() {
        AppConfig config = dynamicWorkflowConfig();
        LocalDateTime t = LocalDateTime.of(2026, 3, 20, 13, 0);

        SequenceRecord stickyTransition = engine.build(List.of(
                detection(1, "AA4444", 100, 10, t),
                detection(2, "AA4444", 200, 10, t.plusMinutes(5)),
                detection(3, "AA4444", 201, 190, t.plusMinutes(10)),
                detection(4, "AA4444", 400, 10, t.plusMinutes(20))
        ), config).getFirst();

        assertThat(stickyTransition.stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut, StageWindow::partial)
                .containsExactly(
                        tuple("service_primary", "Service Primary", t, t.plusMinutes(5).minusSeconds(1), false),
                        tuple("post_3", "Post 3", t.plusMinutes(5), t.plusMinutes(10), false),
                        tuple("service_secondary", "Service Secondary", t.plusMinutes(10).plusSeconds(1), t.plusMinutes(19).plusSeconds(59), false),
                        tuple("parking_secondary", "Parking Secondary", t.plusMinutes(20), null, false)
                );

        SequenceRecord partial = engine.build(List.of(
                detection(1, "AA4445", 401, 10, t)
        ), config).getFirst();

        assertThat(partial.stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::timeIn, StageWindow::timeOut, StageWindow::partial)
                .containsExactly(tuple("parking_secondary", null, t, true));
    }

    @Test
    void shouldInsertIntermediateStageForUnexpectedTransitionPolicy() {
        AppConfig config = dynamicWorkflowConfig();
        AppConfig.StageConfig service = stage(config, "service_primary");
        service.setAllowedNextStages(List.of("post_3"));
        service.setUnexpectedNextStagePolicy("insert_intermediate_and_start_next");
        service.setIntermediateStageOnTransition("backyard_link");

        LocalDateTime t = LocalDateTime.of(2026, 3, 20, 14, 0);
        SequenceRecord record = engine.build(List.of(
                detection(1, "AA5555", 100, 10, t),
                detection(2, "AA5555", 400, 10, t.plusMinutes(4))
        ), config).getFirst();

        assertThat(record.stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::reportLabel, StageWindow::timeIn, StageWindow::timeOut)
                .containsExactly(
                        tuple("service_primary", "Service Primary", t, t.plusMinutes(4).minusSeconds(1)),
                        tuple("backyard_link", "Backyard Link", t.plusMinutes(4), t.plusMinutes(4)),
                        tuple("parking_secondary", "Parking Secondary", t.plusMinutes(4), null)
                );
    }

    @Test
    void shouldNormalizeEqualTimestampsAndCloseSequenceByStageTimeout() {
        AppConfig config = dynamicWorkflowConfig();
        stage(config, "post_3").setSequenceCloseTimeoutMinutes(30);
        LocalDateTime t = LocalDateTime.of(2026, 3, 20, 15, 0);

        List<SequenceRecord> records = engine.build(List.of(
                detection(1, "AA6666", 100, 10, t),
                detection(2, "AA6666", 200, 10, t),
                detection(3, "AA6666", 500, 10, t.plusMinutes(45))
        ), config);

        assertThat(records).hasSize(2);
        assertThat(records.getFirst().stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::timeIn)
                .containsExactly(
                        tuple("service_primary", t),
                        tuple("post_3", t.plusSeconds(1))
                );
        assertThat(records.get(1).stagesChronologically())
                .extracting(StageWindow::stageName, StageWindow::timeIn)
                .containsExactly(tuple("test_drive_candidate", t.plusMinutes(45)));
    }

    private AppConfig baseConfigWithLegacyCameras() {
        AppConfig config = new AppConfig();
        AppConfig.TimingConfig timing = new AppConfig.TimingConfig();
        timing.setDriveInToDriveOutAlertMinutes(15);
        timing.setServiceToPostAlertMinutes(15);
        timing.setTestDriveStartMinutes(10);
        timing.setTestDriveResetMinutes(60);
        config.setTiming(timing);

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
        post.setPostName("Post 1");
        post.setAnalyticsId(20);
        post.setInDirectionRange(range(0, 180));
        post.setOutDirectionRange(range(181, 360));
        cameras.setServicePosts(List.of(post));
        config.setCameras(cameras);
        return config;
    }

    private AppConfig dynamicWorkflowConfig() {
        AppConfig config = new AppConfig();
        AppConfig.WorkflowConfig workflow = new AppConfig.WorkflowConfig();
        workflow.setDefaultSequenceCloseTimeoutMinutes(48 * 60);
        List<AppConfig.StageConfig> stages = new ArrayList<>();
        stages.add(stage("service_primary", "Service Primary", List.of(start(100, null, null, "SERVICE_PRIMARY_IN")), List.of(), false));
        stages.add(stage("service_secondary", "Service Secondary", List.of(start(300, null, null, "SERVICE_SECONDARY_IN")), List.of(), false));
        AppConfig.StageConfig post = stage("post_3", "Post {{instance}}", List.of(start(200, 0, 180, "POST_3_IN", "3")), List.of(finish(201, 180, 360, "POST_3_OUT", "3")), false);
        post.setFinishMode("sticky");
        post.setStickyCloseTimeoutMinutes(5);
        post.setTimeoutTransitionToStage("service_secondary");
        post.setAllowedNextStages(List.of("service_secondary", "parking_secondary"));
        stages.add(post);
        AppConfig.StageConfig parking = stage("parking_secondary", "Parking Secondary", List.of(start(400, null, null, "PARKING_SECONDARY_IN")), List.of(finish(401, null, null, "PARKING_SECONDARY_OUT")), false);
        parking.setAllowPartialFromFinish(true);
        stages.add(parking);
        AppConfig.StageConfig backyard = stage("backyard_link", "Backyard Link", List.of(start(450, null, null, "BACKYARD_LINK_IN")), List.of(), true);
        backyard.setSaveStageAfterSequenceClosed(true);
        stages.add(backyard);
        AppConfig.StageConfig candidate = stage("test_drive_candidate", "Test Drive Candidate", List.of(start(500, null, null, "TEST_DRIVE_CANDIDATE")), List.of(), false);
        candidate.setStartMode("candidate");
        candidate.setCandidateTimeoutMinutes(10);
        candidate.setCandidateCloseTimeoutMinutes(60);
        candidate.setCandidateCancelOnEvents(List.of("SERVICE_PRIMARY_IN"));
        candidate.setSaveStageAfterSequenceClosed(true);
        stages.add(candidate);
        workflow.setStages(stages);
        config.setWorkflow(workflow);
        return config;
    }

    private AppConfig enrich(AppConfig config) {
        config.setWorkflow(workflowDefaultsFactory.buildWorkflow(config));
        return config;
    }

    private AppConfig.StageConfig stage(String name,
                                        String label,
                                        List<AppConfig.TriggerConfig> starts,
                                        List<AppConfig.TriggerConfig> finishes,
                                        boolean transitional) {
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName(name);
        stage.setLabelTemplate(label);
        stage.setStartTriggers(starts);
        stage.setFinishTriggers(finishes);
        stage.setTransitional(transitional);
        stage.setAllowPartialFromFinish(!finishes.isEmpty());
        return stage;
    }

    private AppConfig.TriggerConfig start(int cameraId, Integer from, Integer to, String eventKey) {
        return start(cameraId, from, to, eventKey, null);
    }

    private AppConfig.TriggerConfig start(int cameraId, Integer from, Integer to, String eventKey, String instance) {
        AppConfig.TriggerConfig trigger = new AppConfig.TriggerConfig();
        trigger.setCameraId(cameraId);
        trigger.setDirectionRange(from == null || to == null ? null : range(from, to));
        trigger.setEventType("in");
        trigger.setEventKey(eventKey);
        trigger.setDerivedStageInstance(instance);
        return trigger;
    }

    private AppConfig.TriggerConfig finish(int cameraId, Integer from, Integer to, String eventKey) {
        return finish(cameraId, from, to, eventKey, null);
    }

    private AppConfig.TriggerConfig finish(int cameraId, Integer from, Integer to, String eventKey, String instance) {
        AppConfig.TriggerConfig trigger = new AppConfig.TriggerConfig();
        trigger.setCameraId(cameraId);
        trigger.setDirectionRange(from == null || to == null ? null : range(from, to));
        trigger.setEventType("out");
        trigger.setEventKey(eventKey);
        trigger.setDerivedStageInstance(instance);
        return trigger;
    }

    private AppConfig.StageConfig stage(AppConfig config, String name) {
        return config.getWorkflow().getStages().stream()
                .filter(stage -> stage.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Detection detection(long id, String plate, int cameraId, int direction, LocalDateTime at) {
        return new Detection(id, plate, cameraId, direction, at);
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
