package script.winnerCustomization.dto;
 
public class AlertDto {
 
    private long id;
    private String plateNumber;
    private String message;
    private int timeoutSeconds;
    private boolean active;
 
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
}