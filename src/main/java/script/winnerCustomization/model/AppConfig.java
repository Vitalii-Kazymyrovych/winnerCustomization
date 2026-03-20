package script.winnerCustomization.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private DatabaseConfig sourceDatabase;
    private DatabaseConfig sequenceDatabase;
    private RootDatabaseConfig rootDatabase;
    private SourceTableConfig sourceTable;
    private NotificationsConfig notifications;
    private TimingConfig timing;
    private CamerasConfig cameras;
    private ReportsConfig reports;
    private WorkflowConfig workflow;

    public DatabaseConfig getSourceDatabase() {
        return sourceDatabase;
    }

    public void setSourceDatabase(DatabaseConfig sourceDatabase) {
        this.sourceDatabase = sourceDatabase;
    }

    public DatabaseConfig getSequenceDatabase() {
        return sequenceDatabase;
    }

    public void setSequenceDatabase(DatabaseConfig sequenceDatabase) {
        this.sequenceDatabase = sequenceDatabase;
    }

    public RootDatabaseConfig getRootDatabase() {
        return rootDatabase;
    }

    public void setRootDatabase(RootDatabaseConfig rootDatabase) {
        this.rootDatabase = rootDatabase;
    }

    public SourceTableConfig getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(SourceTableConfig sourceTable) {
        this.sourceTable = sourceTable;
    }

    public NotificationsConfig getNotifications() {
        return notifications;
    }

    public void setNotifications(NotificationsConfig notifications) {
        this.notifications = notifications;
    }

    public TimingConfig getTiming() {
        return timing;
    }

    public void setTiming(TimingConfig timing) {
        this.timing = timing;
    }

    public CamerasConfig getCameras() {
        return cameras;
    }

    public void setCameras(CamerasConfig cameras) {
        this.cameras = cameras;
    }

    public ReportsConfig getReports() {
        return reports;
    }

    public void setReports(ReportsConfig reports) {
        this.reports = reports;
    }

    public WorkflowConfig getWorkflow() {
        return workflow;
    }

    public void setWorkflow(WorkflowConfig workflow) {
        this.workflow = workflow;
    }

    public static class DatabaseConfig {
        private String host;
        private int port;
        private String db;
        private String schema;
        private String user;
        private String password;

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getDb() { return db; }
        public void setDb(String db) { this.db = db; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class RootDatabaseConfig {
        private String host;
        private int port;
        private String user;
        private String password;
        private String maintenanceDb = "postgres";

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + maintenanceDb;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getMaintenanceDb() { return maintenanceDb; }
        public void setMaintenanceDb(String maintenanceDb) { this.maintenanceDb = maintenanceDb; }
    }

    public static class SourceTableConfig {
        private String table;
        private LocalDateTime loadFrom;

        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public LocalDateTime getLoadFrom() { return loadFrom; }
        public void setLoadFrom(LocalDateTime loadFrom) { this.loadFrom = loadFrom; }
    }

    public static class NotificationsConfig {
        private boolean enabled;
        private String telegramBotToken;
        private String telegramChatId;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTelegramBotToken() { return telegramBotToken; }
        public void setTelegramBotToken(String telegramBotToken) { this.telegramBotToken = telegramBotToken; }
        public String getTelegramChatId() { return telegramChatId; }
        public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
    }

    public static class TimingConfig {
        private int driveInToDriveOutAlertMinutes = 15;
        private int serviceToPostAlertMinutes = 15;
        private int testDriveStartMinutes = 30;
        private int testDriveResetMinutes = 60;

        public int getDriveInToDriveOutAlertMinutes() { return driveInToDriveOutAlertMinutes; }
        public void setDriveInToDriveOutAlertMinutes(int driveInToDriveOutAlertMinutes) { this.driveInToDriveOutAlertMinutes = driveInToDriveOutAlertMinutes; }
        public int getServiceToPostAlertMinutes() { return serviceToPostAlertMinutes; }
        public void setServiceToPostAlertMinutes(int serviceToPostAlertMinutes) { this.serviceToPostAlertMinutes = serviceToPostAlertMinutes; }
        public int getTestDriveStartMinutes() { return testDriveStartMinutes; }
        public void setTestDriveStartMinutes(int testDriveStartMinutes) { this.testDriveStartMinutes = testDriveStartMinutes; }
        public int getTestDriveResetMinutes() { return testDriveResetMinutes; }
        public void setTestDriveResetMinutes(int testDriveResetMinutes) { this.testDriveResetMinutes = testDriveResetMinutes; }
    }

    public static class CamerasConfig {
        private List<CameraConfig> driveInIn = new ArrayList<>();
        private List<CameraConfig> driveInOut = new ArrayList<>();
        private List<CameraConfig> serviceIn = new ArrayList<>();
        private List<CameraConfig> driveInToService = new ArrayList<>();
        private List<PostCameraConfig> servicePosts = new ArrayList<>();
        private List<CameraConfig> serviceOut = new ArrayList<>();
        private List<CameraConfig> serviceToDriveIn = new ArrayList<>();
        private List<CameraConfig> parkingIn = new ArrayList<>();
        private List<CameraConfig> parkingOut = new ArrayList<>();

        public List<CameraConfig> getDriveInIn() { return driveInIn; }
        public void setDriveInIn(List<CameraConfig> driveInIn) { this.driveInIn = driveInIn; }
        public List<CameraConfig> getDriveInOut() { return driveInOut; }
        public void setDriveInOut(List<CameraConfig> driveInOut) { this.driveInOut = driveInOut; }
        public List<CameraConfig> getServiceIn() { return serviceIn; }
        public void setServiceIn(List<CameraConfig> serviceIn) { this.serviceIn = serviceIn; }
        public List<CameraConfig> getDriveInToService() { return driveInToService; }
        public void setDriveInToService(List<CameraConfig> driveInToService) { this.driveInToService = driveInToService; }
        public List<PostCameraConfig> getServicePosts() { return servicePosts; }
        public void setServicePosts(List<PostCameraConfig> servicePosts) { this.servicePosts = servicePosts; }
        public List<CameraConfig> getServiceOut() { return serviceOut; }
        public void setServiceOut(List<CameraConfig> serviceOut) { this.serviceOut = serviceOut; }
        public List<CameraConfig> getServiceToDriveIn() { return serviceToDriveIn; }
        public void setServiceToDriveIn(List<CameraConfig> serviceToDriveIn) { this.serviceToDriveIn = serviceToDriveIn; }
        public List<CameraConfig> getParkingIn() { return parkingIn; }
        public void setParkingIn(List<CameraConfig> parkingIn) { this.parkingIn = parkingIn; }
        public List<CameraConfig> getParkingOut() { return parkingOut; }
        public void setParkingOut(List<CameraConfig> parkingOut) { this.parkingOut = parkingOut; }
    }

    public static class ReportsConfig {
        private String outputDirectory;

        public String getOutputDirectory() {
            return outputDirectory;
        }

        public void setOutputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
        }
    }

    public static class WorkflowConfig {
        private int defaultSequenceCloseTimeoutMinutes = 48 * 60;
        private List<StageConfig> stages = new ArrayList<>();

        public int getDefaultSequenceCloseTimeoutMinutes() {
            return defaultSequenceCloseTimeoutMinutes;
        }

        public void setDefaultSequenceCloseTimeoutMinutes(int defaultSequenceCloseTimeoutMinutes) {
            this.defaultSequenceCloseTimeoutMinutes = defaultSequenceCloseTimeoutMinutes;
        }

        public List<StageConfig> getStages() {
            return stages;
        }

        public void setStages(List<StageConfig> stages) {
            this.stages = stages;
        }
    }

    public static class StageConfig {
        private String name;
        private String labelTemplate;
        private List<TriggerConfig> startTriggers = new ArrayList<>();
        private List<TriggerConfig> finishTriggers = new ArrayList<>();
        private String startMode = "immediate";
        private Integer candidateTimeoutMinutes;
        private Integer candidateCloseTimeoutMinutes;
        private List<String> candidateCancelOnEvents = new ArrayList<>();
        private String finishMode = "immediate";
        private Integer stickyCloseTimeoutMinutes;
        private List<String> allowedNextStages = new ArrayList<>();
        private String unexpectedNextStagePolicy = "close_current_and_start_next";
        private String timeoutTransitionToStage;
        private Integer sequenceCloseTimeoutMinutes;
        private Boolean saveStageAfterSequenceClosed = true;
        private boolean allowPartialFromFinish;
        private String startDuplicatePolicy = "ignore";
        private String finishDuplicatePolicy = "update_sticky";
        private String intermediateStageOnTransition;
        private boolean transitional;
        private Integer sameStageReopenAfterMinutes;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabelTemplate() { return labelTemplate; }
        public void setLabelTemplate(String labelTemplate) { this.labelTemplate = labelTemplate; }
        public List<TriggerConfig> getStartTriggers() { return startTriggers; }
        public void setStartTriggers(List<TriggerConfig> startTriggers) { this.startTriggers = startTriggers; }
        public List<TriggerConfig> getFinishTriggers() { return finishTriggers; }
        public void setFinishTriggers(List<TriggerConfig> finishTriggers) { this.finishTriggers = finishTriggers; }
        public String getStartMode() { return startMode; }
        public void setStartMode(String startMode) { this.startMode = startMode; }
        public Integer getCandidateTimeoutMinutes() { return candidateTimeoutMinutes; }
        public void setCandidateTimeoutMinutes(Integer candidateTimeoutMinutes) { this.candidateTimeoutMinutes = candidateTimeoutMinutes; }
        public Integer getCandidateCloseTimeoutMinutes() { return candidateCloseTimeoutMinutes; }
        public void setCandidateCloseTimeoutMinutes(Integer candidateCloseTimeoutMinutes) { this.candidateCloseTimeoutMinutes = candidateCloseTimeoutMinutes; }
        public List<String> getCandidateCancelOnEvents() { return candidateCancelOnEvents; }
        public void setCandidateCancelOnEvents(List<String> candidateCancelOnEvents) { this.candidateCancelOnEvents = candidateCancelOnEvents; }
        public String getFinishMode() { return finishMode; }
        public void setFinishMode(String finishMode) { this.finishMode = finishMode; }
        public Integer getStickyCloseTimeoutMinutes() { return stickyCloseTimeoutMinutes; }
        public void setStickyCloseTimeoutMinutes(Integer stickyCloseTimeoutMinutes) { this.stickyCloseTimeoutMinutes = stickyCloseTimeoutMinutes; }
        public List<String> getAllowedNextStages() { return allowedNextStages; }
        public void setAllowedNextStages(List<String> allowedNextStages) { this.allowedNextStages = allowedNextStages; }
        public String getUnexpectedNextStagePolicy() { return unexpectedNextStagePolicy; }
        public void setUnexpectedNextStagePolicy(String unexpectedNextStagePolicy) { this.unexpectedNextStagePolicy = unexpectedNextStagePolicy; }
        public String getTimeoutTransitionToStage() { return timeoutTransitionToStage; }
        public void setTimeoutTransitionToStage(String timeoutTransitionToStage) { this.timeoutTransitionToStage = timeoutTransitionToStage; }
        public Integer getSequenceCloseTimeoutMinutes() { return sequenceCloseTimeoutMinutes; }
        public void setSequenceCloseTimeoutMinutes(Integer sequenceCloseTimeoutMinutes) { this.sequenceCloseTimeoutMinutes = sequenceCloseTimeoutMinutes; }
        public Boolean getSaveStageAfterSequenceClosed() { return saveStageAfterSequenceClosed; }
        public void setSaveStageAfterSequenceClosed(Boolean saveStageAfterSequenceClosed) { this.saveStageAfterSequenceClosed = saveStageAfterSequenceClosed; }
        public boolean isAllowPartialFromFinish() { return allowPartialFromFinish; }
        public void setAllowPartialFromFinish(boolean allowPartialFromFinish) { this.allowPartialFromFinish = allowPartialFromFinish; }
        public String getStartDuplicatePolicy() { return startDuplicatePolicy; }
        public void setStartDuplicatePolicy(String startDuplicatePolicy) { this.startDuplicatePolicy = startDuplicatePolicy; }
        public String getFinishDuplicatePolicy() { return finishDuplicatePolicy; }
        public void setFinishDuplicatePolicy(String finishDuplicatePolicy) { this.finishDuplicatePolicy = finishDuplicatePolicy; }
        public String getIntermediateStageOnTransition() { return intermediateStageOnTransition; }
        public void setIntermediateStageOnTransition(String intermediateStageOnTransition) { this.intermediateStageOnTransition = intermediateStageOnTransition; }
        public boolean isTransitional() { return transitional; }
        public void setTransitional(boolean transitional) { this.transitional = transitional; }
        public Integer getSameStageReopenAfterMinutes() { return sameStageReopenAfterMinutes; }
        public void setSameStageReopenAfterMinutes(Integer sameStageReopenAfterMinutes) { this.sameStageReopenAfterMinutes = sameStageReopenAfterMinutes; }
    }

    public static class TriggerConfig {
        private Integer cameraId;
        private DirectionRange directionRange;
        private String eventType;
        private String eventKey;
        private NotificationRule notification;
        private String derivedStageInstance;
        private String name;

        public Integer getCameraId() { return cameraId; }
        public void setCameraId(Integer cameraId) { this.cameraId = cameraId; }
        public DirectionRange getDirectionRange() { return directionRange; }
        public void setDirectionRange(DirectionRange directionRange) { this.directionRange = directionRange; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getEventKey() { return eventKey; }
        public void setEventKey(String eventKey) { this.eventKey = eventKey; }
        public NotificationRule getNotification() { return notification; }
        public void setNotification(NotificationRule notification) { this.notification = notification; }
        public String getDerivedStageInstance() { return derivedStageInstance; }
        public void setDerivedStageInstance(String derivedStageInstance) { this.derivedStageInstance = derivedStageInstance; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class NotificationRule {
        private boolean enabled;
        private String template;
        private Integer delayMinutes;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTemplate() { return template; }
        public void setTemplate(String template) { this.template = template; }
        public Integer getDelayMinutes() { return delayMinutes; }
        public void setDelayMinutes(Integer delayMinutes) { this.delayMinutes = delayMinutes; }
    }

    public static class PostCameraConfig {
        private String postName;
        private int analyticsId;
        private DirectionRange inDirectionRange;
        private DirectionRange outDirectionRange;

        public String getPostName() { return postName; }
        public void setPostName(String postName) { this.postName = postName; }
        public int getAnalyticsId() { return analyticsId; }
        public void setAnalyticsId(int analyticsId) { this.analyticsId = analyticsId; }
        public DirectionRange getInDirectionRange() { return inDirectionRange; }
        public void setInDirectionRange(DirectionRange inDirectionRange) { this.inDirectionRange = inDirectionRange; }
        public DirectionRange getOutDirectionRange() { return outDirectionRange; }
        public void setOutDirectionRange(DirectionRange outDirectionRange) { this.outDirectionRange = outDirectionRange; }

        public boolean matchesIn(Detection detection) {
            return analyticsId == detection.analyticsId()
                    && (inDirectionRange == null || inDirectionRange.contains(detection.direction()));
        }

        public boolean matchesOut(Detection detection) {
            return analyticsId == detection.analyticsId()
                    && (outDirectionRange == null || outDirectionRange.contains(detection.direction()));
        }
    }

    public static class CameraConfig {
        private String name;
        private int analyticsId;
        private DirectionRange directionRange;

        public boolean matchesDirection(Integer direction) {
            return directionRange == null || directionRange.contains(direction);
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAnalyticsId() { return analyticsId; }
        public void setAnalyticsId(int analyticsId) { this.analyticsId = analyticsId; }
        public DirectionRange getDirectionRange() { return directionRange; }
        public void setDirectionRange(DirectionRange directionRange) { this.directionRange = directionRange; }
    }

    public static class DirectionRange {
        private Integer from;
        private Integer to;

        public boolean contains(Integer direction) {
            if (from == null || to == null || direction == null) {
                return true;
            }
            if (from.equals(to)) {
                return direction.equals(from);
            }
            if (from < to) {
                return direction >= from && direction < to;
            }
            return direction >= from || direction < to;
        }

        public Integer getFrom() { return from; }
        public void setFrom(Integer from) { this.from = from; }
        public Integer getTo() { return to; }
        public void setTo(Integer to) { this.to = to; }
    }
}
