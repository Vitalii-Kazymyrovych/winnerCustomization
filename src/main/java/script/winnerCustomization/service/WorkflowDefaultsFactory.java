package script.winnerCustomization.service;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class WorkflowDefaultsFactory {

    public AppConfig enrich(AppConfig config) {
        if (config == null) {
            return null;
        }
        if (config.getWorkflow() == null || config.getWorkflow().getStages() == null || config.getWorkflow().getStages().isEmpty()) {
            config.setWorkflow(buildWorkflow(config));
        }
        return config;
    }

    public AppConfig.WorkflowConfig buildWorkflow(AppConfig config) {
        AppConfig.WorkflowConfig workflow = new AppConfig.WorkflowConfig();
        workflow.setDefaultSequenceCloseTimeoutMinutes(48 * 60);
        List<AppConfig.StageConfig> stages = new ArrayList<>();
        AppConfig.StageConfig driveIn = driveInStage(config);
        AppConfig.StageConfig service = serviceStage(config);
        List<AppConfig.StageConfig> posts = postStages(config);
        AppConfig.StageConfig parking = parkingStage(config);
        AppConfig.StageConfig backyard = backyardStage(config);
        AppConfig.StageConfig testDrive = testDriveStage(config);

        driveIn.setAllowedNextStages(List.of(service.getName(), backyard.getName(), parking.getName(), testDrive.getName()));
        service.setAllowedNextStages(buildAllowedServiceNext(posts, backyard, testDrive));
        for (AppConfig.StageConfig post : posts) {
            post.setAllowedNextStages(buildAllowedPostNext(posts, service, parking, backyard, driveIn));
            post.setUnexpectedNextStagePolicy("close_current_and_start_next");
        }
        parking.setAllowedNextStages(List.of(backyard.getName(), driveIn.getName(), service.getName()));
        backyard.setAllowedNextStages(List.of(driveIn.getName(), service.getName(), parking.getName()));
        testDrive.setAllowedNextStages(List.of(driveIn.getName(), service.getName(), parking.getName(), backyard.getName()));

        stages.add(driveIn);
        stages.add(service);
        stages.addAll(posts);
        stages.add(parking);
        stages.add(backyard);
        stages.add(testDrive);
        workflow.setStages(stages);
        return workflow;
    }

    private AppConfig.StageConfig driveInStage(AppConfig config) {
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName("drive_in");
        stage.setLabelTemplate("Drive In");
        stage.setAllowPartialFromFinish(true);
        stage.setStartTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getDriveInIn(), "in", "DRIVE_IN_IN", null,
                notification("No Drive in (out) within {{threshold}} minutes", config.getTiming() == null ? 15 : config.getTiming().getDriveInToDriveOutAlertMinutes())));
        stage.setFinishTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getDriveInOut(), "out", "DRIVE_IN_OUT", null, null));
        return stage;
    }

    private AppConfig.StageConfig serviceStage(AppConfig config) {
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName("service");
        stage.setLabelTemplate("Service");
        stage.setAllowPartialFromFinish(true);
        stage.setIntermediateStageOnTransition("backyard");
        stage.setStartTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getServiceIn(), "in", "SERVICE_IN", null,
                notification("No Post in within {{threshold}} minutes", config.getTiming() == null ? 15 : config.getTiming().getServiceToPostAlertMinutes())));
        stage.setFinishTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getServiceOut(), "out", "SERVICE_OUT", null, null));
        return stage;
    }

    private List<AppConfig.StageConfig> postStages(AppConfig config) {
        List<AppConfig.StageConfig> stages = new ArrayList<>();
        if (config.getCameras() == null || config.getCameras().getServicePosts() == null) {
            return stages;
        }
        for (AppConfig.PostCameraConfig postCamera : config.getCameras().getServicePosts()) {
            AppConfig.StageConfig stage = new AppConfig.StageConfig();
            String instance = postCamera.getPostName();
            stage.setName("post_" + sanitize(instance));
            stage.setLabelTemplate("{{instance}}");
            stage.setFinishMode("sticky");
            stage.setStickyCloseTimeoutMinutes(config.getTiming() == null ? 60 : config.getTiming().getTestDriveResetMinutes());
            stage.setAllowPartialFromFinish(true);
            stage.setTimeoutTransitionToStage("service");
            stage.setStartTriggers(List.of(postTrigger(stage.getName(), postCamera.getAnalyticsId(), postCamera.getInDirectionRange(), "in", stage.getName().toUpperCase(Locale.ROOT) + "_IN", instance)));
            stage.setFinishTriggers(List.of(postTrigger(stage.getName(), postCamera.getAnalyticsId(), postCamera.getOutDirectionRange(), "out", stage.getName().toUpperCase(Locale.ROOT) + "_OUT", instance)));
            stages.add(stage);
        }
        return stages;
    }

    private AppConfig.TriggerConfig postTrigger(String stageName,
                                                int cameraId,
                                                AppConfig.DirectionRange range,
                                                String eventType,
                                                String eventKey,
                                                String instance) {
        AppConfig.TriggerConfig trigger = new AppConfig.TriggerConfig();
        trigger.setName(stageName + "-" + eventType);
        trigger.setCameraId(cameraId);
        trigger.setDirectionRange(range);
        trigger.setEventType(eventType);
        trigger.setEventKey(eventKey);
        trigger.setDerivedStageInstance(instance);
        return trigger;
    }

    private AppConfig.StageConfig parkingStage(AppConfig config) {
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName("parking");
        stage.setLabelTemplate("Parking");
        stage.setAllowPartialFromFinish(true);
        stage.setIntermediateStageOnTransition("backyard");
        stage.setStartTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getParkingIn(), "in", "PARKING_IN", null, null));
        stage.setFinishTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getParkingOut(), "out", "PARKING_OUT", null, null));
        return stage;
    }

    private AppConfig.StageConfig backyardStage(AppConfig config) {
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName("backyard");
        stage.setLabelTemplate("Backyard");
        stage.setTransitional(true);
        stage.setSaveStageAfterSequenceClosed(false);
        stage.setStartTriggers(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getDriveInToService(), "in", "DRIVE_IN_TO_SERVICE", null, null));
        return stage;
    }

    private AppConfig.StageConfig testDriveStage(AppConfig config) {
        AppConfig.StageConfig stage = new AppConfig.StageConfig();
        stage.setName("test_drive");
        stage.setLabelTemplate("Test-Drive");
        stage.setStartMode("candidate");
        int candidateTimeout = config.getTiming() == null ? 30 : config.getTiming().getTestDriveStartMinutes();
        int candidateCloseTimeout = config.getTiming() == null ? 60 : config.getTiming().getTestDriveResetMinutes();
        stage.setCandidateTimeoutMinutes(candidateTimeout);
        stage.setCandidateCloseTimeoutMinutes(candidateCloseTimeout);
        stage.setSaveStageAfterSequenceClosed(false);
        stage.setCandidateCancelOnEvents(List.of("SERVICE_IN", "PARKING_IN", "DRIVE_IN_IN", "DRIVE_IN_TO_SERVICE"));
        List<AppConfig.TriggerConfig> triggers = new ArrayList<>();
        triggers.addAll(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getDriveInOut(), "candidate", "DRIVE_IN_OUT", null, null));
        triggers.addAll(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getServiceToDriveIn(), "candidate", "SERVICE_TO_DRIVE_IN", null, null));
        stage.setStartTriggers(triggers);
        return stage;
    }

    private List<String> buildAllowedServiceNext(List<AppConfig.StageConfig> posts,
                                                 AppConfig.StageConfig backyard,
                                                 AppConfig.StageConfig testDrive) {
        List<String> result = posts.stream().map(AppConfig.StageConfig::getName).collect(Collectors.toCollection(ArrayList::new));
        result.add(backyard.getName());
        result.add(testDrive.getName());
        return result;
    }

    private List<String> buildAllowedPostNext(List<AppConfig.StageConfig> posts,
                                              AppConfig.StageConfig service,
                                              AppConfig.StageConfig parking,
                                              AppConfig.StageConfig backyard,
                                              AppConfig.StageConfig driveIn) {
        List<String> result = posts.stream().map(AppConfig.StageConfig::getName).collect(Collectors.toCollection(ArrayList::new));
        result.add(service.getName());
        result.add(parking.getName());
        result.add(backyard.getName());
        result.add(driveIn.getName());
        return result;
    }

    private List<AppConfig.TriggerConfig> toTriggers(List<AppConfig.CameraConfig> cameras,
                                                     String eventType,
                                                     String eventKey,
                                                     String derivedInstance,
                                                     AppConfig.NotificationRule notification) {
        List<AppConfig.TriggerConfig> triggers = new ArrayList<>();
        for (AppConfig.CameraConfig camera : cameras) {
            AppConfig.TriggerConfig trigger = new AppConfig.TriggerConfig();
            trigger.setName(camera.getName());
            trigger.setCameraId(camera.getAnalyticsId());
            trigger.setDirectionRange(camera.getDirectionRange());
            trigger.setEventType(eventType);
            trigger.setEventKey(eventKey);
            trigger.setDerivedStageInstance(derivedInstance);
            trigger.setNotification(notification);
            triggers.add(trigger);
        }
        return triggers;
    }

    private AppConfig.NotificationRule notification(String template, int minutes) {
        AppConfig.NotificationRule notification = new AppConfig.NotificationRule();
        notification.setEnabled(true);
        notification.setTemplate(template);
        notification.setDelayMinutes(minutes);
        return notification;
    }

    private String sanitize(String value) {
        return value == null ? "post" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
