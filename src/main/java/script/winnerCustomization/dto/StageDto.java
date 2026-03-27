package script.winnerCustomization.dto;
 
import java.time.LocalDateTime;
import java.util.List;
 
public class StageDto {
 
    private long id;
    private String name;
    private String label;
    private String type;
    private boolean active;
    private boolean full;
    private int timeout;
    private LocalDateTime inTime;
    private LocalDateTime outTime;
    private Long durationSeconds;
    private String plateNumber;
    private List<AlertDto> alerts;
 
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
 
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
 
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
 
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
 
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
 
    public boolean isFull() { return full; }
    public void setFull(boolean full) { this.full = full; }
 
    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
 
    public LocalDateTime getInTime() { return inTime; }
    public void setInTime(LocalDateTime inTime) { this.inTime = inTime; }
 
    public LocalDateTime getOutTime() { return outTime; }
    public void setOutTime(LocalDateTime outTime) { this.outTime = outTime; }
 
    public Long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Long durationSeconds) { this.durationSeconds = durationSeconds; }
 
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
 
    public List<AlertDto> getAlerts() { return alerts; }
    public void setAlerts(List<AlertDto> alerts) { this.alerts = alerts; }
}