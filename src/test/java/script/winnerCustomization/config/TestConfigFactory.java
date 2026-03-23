package script.winnerCustomization.config;

import script.winnerCustomization.model.AppConfig;

import java.util.List;

public final class TestConfigFactory {
    private TestConfigFactory() {
    }

    public static AppConfig standardConfig() {
        AppConfig config = new AppConfig();
        AppConfig.DatabaseConfig source = new AppConfig.DatabaseConfig();
        source.setHost("localhost");
        source.setPort(5432);
        source.setDb("source");
        source.setSchema("videoanalytics");
        source.setUser("user");
        source.setPassword("pass");
        config.setSourceDatabase(source);
        config.setSequenceDatabase(source);
        AppConfig.RootDatabaseConfig root = new AppConfig.RootDatabaseConfig();
        root.setHost("localhost");
        root.setPort(5432);
        root.setUser("postgres");
        root.setPassword("postgres");
        config.setRootDatabase(root);
        AppConfig.SourceTableConfig sourceTable = new AppConfig.SourceTableConfig();
        sourceTable.setTable("alpr_detections");
        config.setSourceTable(sourceTable);
        AppConfig.ReportConfig reports = new AppConfig.ReportConfig();
        reports.setOutputDirectory("./reports");
        config.setReports(reports);
        AppConfig.MessagingConfig messaging = new AppConfig.MessagingConfig();
        messaging.setEnabled(false);
        config.setMessaging(messaging);
        config.setSequenceCloseTimeoutMinutes(30);

        AppConfig.RealStageConfig driveIn = new AppConfig.RealStageConfig();
        driveIn.setName("drive_in");
        driveIn.setLabel("Drive In");
        driveIn.setInTriggers(List.of(trigger(1001, 0, 180)));
        driveIn.setOutTriggers(List.of(trigger(1002, 180, 360)));

        AppConfig.RealStageConfig service = new AppConfig.RealStageConfig();
        service.setName("service");
        service.setLabel("Service");
        service.setInTriggers(List.of(trigger(1003, null, null)));
        service.setOutTriggers(List.of(trigger(1004, null, null)));

        AppConfig.TransitionalStageConfig backyard = new AppConfig.TransitionalStageConfig();
        backyard.setName("backyard");
        backyard.setLabel("Backyard");
        backyard.setTriggerCameras(List.of(2001));
        backyard.setAllowedAfter(List.of("service", "post_1"));
        backyard.setCandidateTimeoutSeconds(10);
        backyard.setShowInReportIfIncomplete(false);

        AppConfig.TransitionalStageConfig testDrive = new AppConfig.TransitionalStageConfig();
        testDrive.setName("test_drive");
        testDrive.setLabel("Test-Drive");
        testDrive.setTriggerCameras(List.of(2002));
        testDrive.setAllowedAfter(List.of("backyard"));
        testDrive.setCandidateTimeoutSeconds(5);
        testDrive.setSequenceCloseTimeoutOverrideSeconds(15);
        testDrive.setShowInReportIfIncomplete(true);

        AppConfig.SingleCameraStageConfig post1 = new AppConfig.SingleCameraStageConfig();
        post1.setName("post_1");
        post1.setLabel("Post 1");
        post1.setCameraId(3001);
        post1.setTimeoutSeconds(5);

        AppConfig.NotificationRule driveInAlarm = new AppConfig.NotificationRule();
        driveInAlarm.setCameraId(1001);
        driveInAlarm.setDirectionRange(range(0, 180));
        driveInAlarm.setDelaySeconds(900);
        driveInAlarm.setMessage("No Drive in (out) within 15 minutes");

        AppConfig.NotificationRule postAlarm = new AppConfig.NotificationRule();
        postAlarm.setCameraId(3001);
        postAlarm.setDelaySeconds(900);
        postAlarm.setMessage("No Post in within 15 minutes");

        config.setRealStages(List.of(driveIn, service));
        config.setTransitionalStages(List.of(backyard, testDrive));
        config.setSingleCameraStages(List.of(post1));
        config.setNotifications(List.of(driveInAlarm, postAlarm));
        return config;
    }

    private static AppConfig.CameraTrigger trigger(int cameraId, Integer from, Integer to) {
        AppConfig.CameraTrigger trigger = new AppConfig.CameraTrigger();
        trigger.setCameraId(cameraId);
        if (from != null || to != null) {
            trigger.setDirectionRange(range(from, to));
        }
        return trigger;
    }

    private static AppConfig.DirectionRange range(Integer from, Integer to) {
        AppConfig.DirectionRange range = new AppConfig.DirectionRange();
        range.setFrom(from);
        range.setTo(to);
        return range;
    }
}
