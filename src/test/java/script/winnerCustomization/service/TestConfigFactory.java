package script.winnerCustomization.service;

import script.winnerCustomization.model.AppConfig;

import java.util.List;

public final class TestConfigFactory {
    private TestConfigFactory() {}

    public static AppConfig config() {
        AppConfig config = new AppConfig();
        AppConfig.DatabaseConfig sourceDb = new AppConfig.DatabaseConfig();
        sourceDb.setSchema("videoanalytics");
        config.setSourceDatabase(sourceDb);
        AppConfig.SourceTableConfig sourceTable = new AppConfig.SourceTableConfig();
        sourceTable.setTable("alpr_detections");
        config.setSourceTable(sourceTable);
        config.setSequenceCloseTimeoutMinutes(60);
        config.setDuplicateSuppressionSeconds(1);

        AppConfig.RealStageConfig driveIn = new AppConfig.RealStageConfig();
        driveIn.setName("drive_in");
        driveIn.setLabel("Drive In");
        driveIn.setInTriggers(List.of(trigger(1001, 0, 180)));
        driveIn.setOutTriggers(List.of(trigger(1002, 180, 360)));

        AppConfig.RealStageConfig service = new AppConfig.RealStageConfig();
        service.setName("service");
        service.setLabel("Service");
        service.setInTriggers(List.of(trigger(1003, 0, 360)));
        service.setOutTriggers(List.of(trigger(1005, 0, 180), trigger(1005, 180, 360)));

        AppConfig.TransitionalStageConfig backyard = new AppConfig.TransitionalStageConfig();
        backyard.setName("backyard");
        backyard.setLabel("Backyard");
        backyard.setTriggerCameras(List.of(1008));
        backyard.setCandidateTimeoutSeconds(2);
        backyard.setAllowedAfter(List.of("drive_in", "service"));
        backyard.setSequenceCloseTimeoutOverrideSeconds(0);
        backyard.setShowInReportIfIncomplete(false);

        AppConfig.SingleCameraStageConfig post1 = new AppConfig.SingleCameraStageConfig();
        post1.setName("post_1");
        post1.setLabel("Post 1");
        post1.setCameraId(1101);
        post1.setTimeoutSeconds(30);

        AppConfig.NotificationRule notification = new AppConfig.NotificationRule();
        notification.setCameraId(1001);
        notification.setDelaySeconds(900);
        notification.setMessage("Автомобіль довго стоїть на Drive-In");

        config.setRealStages(List.of(driveIn, service));
        config.setTransitionalStages(List.of(backyard));
        config.setSingleCameraStages(List.of(post1));
        config.setNotifications(List.of(notification));
        return config;
    }

    private static AppConfig.CameraTrigger trigger(int cameraId, int from, int to) {
        AppConfig.CameraTrigger trigger = new AppConfig.CameraTrigger();
        trigger.setCameraId(cameraId);
        AppConfig.DirectionRange range = new AppConfig.DirectionRange();
        range.setFrom(from);
        range.setTo(to);
        trigger.setDirectionRange(range);
        return trigger;
    }
}
