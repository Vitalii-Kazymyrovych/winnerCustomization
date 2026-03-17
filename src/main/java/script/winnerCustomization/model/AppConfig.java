package script.winnerCustomization.model;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

public class AppConfig {
    private DatabaseConfig sourceDatabase;
    private DatabaseConfig sequenceDatabase;
    private RootDatabaseConfig rootDatabase;
    private SourceTableConfig sourceTable;
    private NotificationsConfig notifications;
    private TimingConfig timing;
    private CamerasConfig cameras;

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
        private List<PostCameraConfig> servicePosts = new ArrayList<>();
        private List<CameraConfig> serviceOut = new ArrayList<>();
        private List<CameraConfig> parkingIn = new ArrayList<>();
        private List<CameraConfig> parkingOut = new ArrayList<>();

        public List<CameraConfig> getDriveInIn() { return driveInIn; }
        public void setDriveInIn(List<CameraConfig> driveInIn) { this.driveInIn = driveInIn; }
        public List<CameraConfig> getDriveInOut() { return driveInOut; }
        public void setDriveInOut(List<CameraConfig> driveInOut) { this.driveInOut = driveInOut; }
        public List<CameraConfig> getServiceIn() { return serviceIn; }
        public void setServiceIn(List<CameraConfig> serviceIn) { this.serviceIn = serviceIn; }
        public List<PostCameraConfig> getServicePosts() { return servicePosts; }
        public void setServicePosts(List<PostCameraConfig> servicePosts) { this.servicePosts = servicePosts; }
        public List<CameraConfig> getServiceOut() { return serviceOut; }
        public void setServiceOut(List<CameraConfig> serviceOut) { this.serviceOut = serviceOut; }
        public List<CameraConfig> getParkingIn() { return parkingIn; }
        public void setParkingIn(List<CameraConfig> parkingIn) { this.parkingIn = parkingIn; }
        public List<CameraConfig> getParkingOut() { return parkingOut; }
        public void setParkingOut(List<CameraConfig> parkingOut) { this.parkingOut = parkingOut; }
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
            return direction >= from && direction <= to;
        }

        public Integer getFrom() { return from; }
        public void setFrom(Integer from) { this.from = from; }
        public Integer getTo() { return to; }
        public void setTo(Integer to) { this.to = to; }
    }
}
