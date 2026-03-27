package script.winnerCustomization.model;
 
/**
 * Represents an alert instance for a plate detection.
 */
public class AlertRecord {
 
    private long id;
    private String plateNumber;
    private String message;
    private int timeoutSeconds; // seconds remaining before sending
    private boolean active;
    private int triggerAnalyticsId;
 
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
 
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
 
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
 
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
 
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
 
    public int getTriggerAnalyticsId() { return triggerAnalyticsId; }
    public void setTriggerAnalyticsId(int triggerAnalyticsId) { this.triggerAnalyticsId = triggerAnalyticsId; }
 
    @Override
    public String toString() {
        return "AlertRecord{plate='" + plateNumber + "', message='" + message +
                "', timeout=" + timeoutSeconds + ", active=" + active + "}";
    }
}