package script.winnerCustomization.model;
 
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
 
/**
 * Represents a stage within a sequence. Covers real, transitional, and singleCamera types.
 */
public class Stage {
 
    private long id;
    private String name;
    private String label;
    private String type; // "real", "transitional", "singleCamera"
    private boolean active;
    private boolean full;
    private boolean candidate; // true for transitional candidates not yet materialized
    private int timeout; // seconds remaining for transitional candidates / alerts
    private LocalDateTime inTime;
    private LocalDateTime outTime;
    private Long durationSeconds;
    private String plateNumber;
    private List<AlertRecord> alerts = new ArrayList<>();
 
    // For transitional: reference to the config for timeout override
    private int sequenceCloseTimeoutOverrideMinutes;
 
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
 
    public boolean isCandidate() { return candidate; }
    public void setCandidate(boolean candidate) { this.candidate = candidate; }
 
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
 
    public List<AlertRecord> getAlerts() { return alerts; }
    public void setAlerts(List<AlertRecord> alerts) { this.alerts = alerts; }
 
    public int getSequenceCloseTimeoutOverrideMinutes() { return sequenceCloseTimeoutOverrideMinutes; }
    public void setSequenceCloseTimeoutOverrideMinutes(int v) { this.sequenceCloseTimeoutOverrideMinutes = v; }
 
    public void recalculateDuration(LocalDateTime now) {
        if ("singleCamera".equals(type)) {
            if (inTime != null && outTime != null) {
                durationSeconds = Duration.between(inTime, outTime).getSeconds();
            }
            return;
        }
        if (inTime == null) {
            durationSeconds = null;
            return;
        }
        if (outTime != null) {
            durationSeconds = Duration.between(inTime, outTime).getSeconds();
        } else if (active) {
            durationSeconds = Duration.between(inTime, now).getSeconds();
        } else {
            durationSeconds = null;
        }
    }
 
    @Override
    public String toString() {
        return "Stage{name='" + name + "', type='" + type + "', active=" + active +
                ", full=" + full + ", candidate=" + candidate +
                ", in=" + inTime + ", out=" + outTime + "}";
    }
}