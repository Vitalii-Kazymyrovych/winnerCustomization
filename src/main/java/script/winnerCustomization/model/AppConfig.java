package script.winnerCustomization.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private DatabaseConfig sourceDatabase;
    private DatabaseConfig sequenceDatabase;
    private RootDatabaseConfig rootDatabase;
    private SourceTableConfig sourceTable;
    private ReportConfig reports;
    private MessagingConfig messaging;
    private Integer sequenceCloseTimeoutMinutes = 2880;
    private List<NotificationRule> notifications = new ArrayList<>();
    private List<RealStageConfig> realStages = new ArrayList<>();
    private List<TransitionalStageConfig> transitionalStages = new ArrayList<>();
    private List<SingleCameraStageConfig> singleCameraStages = new ArrayList<>();

    public DatabaseConfig getSourceDatabase() { return sourceDatabase; }
    public void setSourceDatabase(DatabaseConfig sourceDatabase) { this.sourceDatabase = sourceDatabase; }
    public DatabaseConfig getSequenceDatabase() { return sequenceDatabase; }
    public void setSequenceDatabase(DatabaseConfig sequenceDatabase) { this.sequenceDatabase = sequenceDatabase; }
    public RootDatabaseConfig getRootDatabase() { return rootDatabase; }
    public void setRootDatabase(RootDatabaseConfig rootDatabase) { this.rootDatabase = rootDatabase; }
    public SourceTableConfig getSourceTable() { return sourceTable; }
    public void setSourceTable(SourceTableConfig sourceTable) { this.sourceTable = sourceTable; }
    public ReportConfig getReports() { return reports; }
    public void setReports(ReportConfig reports) { this.reports = reports; }
    public MessagingConfig getMessaging() { return messaging; }
    public void setMessaging(MessagingConfig messaging) { this.messaging = messaging; }
    public Integer getSequenceCloseTimeoutMinutes() { return sequenceCloseTimeoutMinutes; }
    public void setSequenceCloseTimeoutMinutes(Integer sequenceCloseTimeoutMinutes) { this.sequenceCloseTimeoutMinutes = sequenceCloseTimeoutMinutes; }
    public List<NotificationRule> getNotifications() { return notifications; }
    public void setNotifications(List<NotificationRule> notifications) { this.notifications = notifications; }
    public List<RealStageConfig> getRealStages() { return realStages; }
    public void setRealStages(List<RealStageConfig> realStages) { this.realStages = realStages; }
    public List<TransitionalStageConfig> getTransitionalStages() { return transitionalStages; }
    public void setTransitionalStages(List<TransitionalStageConfig> transitionalStages) { this.transitionalStages = transitionalStages; }
    public List<SingleCameraStageConfig> getSingleCameraStages() { return singleCameraStages; }
    public void setSingleCameraStages(List<SingleCameraStageConfig> singleCameraStages) { this.singleCameraStages = singleCameraStages; }

    public static class DatabaseConfig {
        private String host;
        private int port;
        private String db;
        private String schema;
        private String user;
        private String password;

        public String jdbcUrl() { return "jdbc:postgresql://" + host + ":" + port + "/" + db; }
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

        public String jdbcUrl() { return "jdbc:postgresql://" + host + ":" + port + "/" + maintenanceDb; }
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

    public static class ReportConfig {
        private String outputDirectory;
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    }

    public static class MessagingConfig {
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

    public static class DirectionRange {
        private Integer from;
        private Integer to;
        public Integer getFrom() { return from; }
        public void setFrom(Integer from) { this.from = from; }
        public Integer getTo() { return to; }
        public void setTo(Integer to) { this.to = to; }
    }

    public static class CameraTrigger {
        private Integer cameraId;
        private DirectionRange directionRange;
        public Integer getCameraId() { return cameraId; }
        public void setCameraId(Integer cameraId) { this.cameraId = cameraId; }
        public DirectionRange getDirectionRange() { return directionRange; }
        public void setDirectionRange(DirectionRange directionRange) { this.directionRange = directionRange; }
    }

    public static class NotificationRule {
        private Integer cameraId;
        private DirectionRange directionRange;
        private Integer delaySeconds;
        private String message;

        public Integer getCameraId() { return cameraId; }
        public void setCameraId(Integer cameraId) { this.cameraId = cameraId; }
        public DirectionRange getDirectionRange() { return directionRange; }
        public void setDirectionRange(DirectionRange directionRange) { this.directionRange = directionRange; }
        public Integer getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(Integer delaySeconds) { this.delaySeconds = delaySeconds; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class RealStageConfig {
        private String name;
        private String label;
        private List<CameraTrigger> inTriggers = new ArrayList<>();
        private List<CameraTrigger> outTriggers = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<CameraTrigger> getInTriggers() { return inTriggers; }
        public void setInTriggers(List<CameraTrigger> inTriggers) { this.inTriggers = inTriggers; }
        public List<CameraTrigger> getOutTriggers() { return outTriggers; }
        public void setOutTriggers(List<CameraTrigger> outTriggers) { this.outTriggers = outTriggers; }
    }

    public static class TransitionalStageConfig {
        private String name;
        private String label;
        private List<Integer> triggerCameras = new ArrayList<>();
        private Integer candidateTimeoutSeconds;
        private List<String> allowedAfter = new ArrayList<>();
        private Integer sequenceCloseTimeoutOverrideSeconds;
        private Boolean showInReportIfIncomplete = false;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<Integer> getTriggerCameras() { return triggerCameras; }
        public void setTriggerCameras(List<Integer> triggerCameras) { this.triggerCameras = triggerCameras; }
        public Integer getCandidateTimeoutSeconds() { return candidateTimeoutSeconds; }
        public void setCandidateTimeoutSeconds(Integer candidateTimeoutSeconds) { this.candidateTimeoutSeconds = candidateTimeoutSeconds; }
        public List<String> getAllowedAfter() { return allowedAfter; }
        public void setAllowedAfter(List<String> allowedAfter) { this.allowedAfter = allowedAfter; }
        public Integer getSequenceCloseTimeoutOverrideSeconds() { return sequenceCloseTimeoutOverrideSeconds; }
        public void setSequenceCloseTimeoutOverrideSeconds(Integer sequenceCloseTimeoutOverrideSeconds) { this.sequenceCloseTimeoutOverrideSeconds = sequenceCloseTimeoutOverrideSeconds; }
        public Boolean getShowInReportIfIncomplete() { return showInReportIfIncomplete; }
        public void setShowInReportIfIncomplete(Boolean showInReportIfIncomplete) { this.showInReportIfIncomplete = showInReportIfIncomplete; }
    }

    public static class SingleCameraStageConfig {
        private String name;
        private String label;
        private Integer cameraId;
        private Integer timeoutSeconds;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public Integer getCameraId() { return cameraId; }
        public void setCameraId(Integer cameraId) { this.cameraId = cameraId; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
