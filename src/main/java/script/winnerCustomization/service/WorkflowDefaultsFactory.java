package script.winnerCustomization.service;

import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;

import java.util.ArrayList;
import java.util.List;

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
        stages.add(driveInStage(config));
        stages.add(serviceStage(config));
        stages.addAll(postStages(config));
        stages.add(parkingStage(config));
        stages.add(backyardStage(config));
        stages.add(testDriveStage(config));
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
            stage.setName("post_" + sanitize(postCamera.getPostName()));
            stage.setLabelTemplate(postCamera.getPostName());
            stage.setFinishMode("sticky");
            stage.setAllowPartialFromFinish(true);
            stage.setTimeoutTransitionToStage("service");
            AppConfig.TriggerConfig in = new AppConfig.TriggerConfig();
            in.setCameraId(postCamera.getAnalyticsId());
            in.setDirectionRange(postCamera.getInDirectionRange());
            in.setEventType("in");
            in.setEventKey(stage.getName().toUpperCase() + "_IN");
            in.setDerivedStageInstance(postCamera.getPostName());
            AppConfig.TriggerConfig out = new AppConfig.TriggerConfig();
            out.setCameraId(postCamera.getAnalyticsId());
            out.setDirectionRange(postCamera.getOutDirectionRange());
            out.setEventType("out");
            out.setEventKey(stage.getName().toUpperCase() + "_OUT");
            out.setDerivedStageInstance(postCamera.getPostName());
            stage.setStartTriggers(List.of(in));
            stage.setFinishTriggers(List.of(out));
            stages.add(stage);
        }
        return stages;
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
        List<AppConfig.TriggerConfig> triggers = new ArrayList<>();
        triggers.addAll(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getDriveInOut(), "candidate", "DRIVE_IN_OUT", null, null));
        triggers.addAll(toTriggers(config.getCameras() == null ? List.of() : config.getCameras().getServiceToDriveIn(), "candidate", "SERVICE_TO_DRIVE_IN", null, null));
        stage.setStartTriggers(triggers);
        return stage;
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
        return value == null ? "post" : value.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
