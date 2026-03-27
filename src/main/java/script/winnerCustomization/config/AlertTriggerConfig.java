package script.winnerCustomization.config;
 
public class AlertTriggerConfig {
 
    private int analyticsId;
    private Integer direction; // null means any direction
 
    public int getAnalyticsId() { return analyticsId; }
    public void setAnalyticsId(int analyticsId) { this.analyticsId = analyticsId; }
 
    public Integer getDirection() { return direction; }
    public void setDirection(Integer direction) { this.direction = direction; }
}