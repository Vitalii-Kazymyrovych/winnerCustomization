package script.winnerCustomization.model;

import java.time.LocalDateTime;

public class Alert {
    private String plate;
    private int triggerAnalyticsId;
    private int timeoutSeconds;
    private String message;
    private boolean active;
    private LocalDateTime createdAtUtc;

    public String getPlate() { return plate; }
    public void setPlate(String plate) { this.plate = plate; }
    public int getTriggerAnalyticsId() { return triggerAnalyticsId; }
    public void setTriggerAnalyticsId(int triggerAnalyticsId) { this.triggerAnalyticsId = triggerAnalyticsId; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAtUtc() { return createdAtUtc; }
    public void setCreatedAtUtc(LocalDateTime createdAtUtc) { this.createdAtUtc = createdAtUtc; }
}
