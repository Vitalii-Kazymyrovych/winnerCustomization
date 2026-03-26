package script.winnerCustomization.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Stage {
    private String name;
    private String label;
    private StageType type;
    private boolean active;
    private boolean full;
    private int timeoutSeconds;
    private String plate;
    private LocalDateTime inTime;
    private LocalDateTime outTime;
    private LocalDateTime lastDetectionTime;
    private Duration duration;
    private int sequenceCloseTimeoutOverrideMinutes;
    private final List<String> alerts = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public StageType getType() { return type; }
    public void setType(StageType type) { this.type = type; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isFull() { return full; }
    public void setFull(boolean full) { this.full = full; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getPlate() { return plate; }
    public void setPlate(String plate) { this.plate = plate; }
    public LocalDateTime getInTime() { return inTime; }
    public void setInTime(LocalDateTime inTime) { this.inTime = inTime; }
    public LocalDateTime getOutTime() { return outTime; }
    public void setOutTime(LocalDateTime outTime) { this.outTime = outTime; }
    public LocalDateTime getLastDetectionTime() { return lastDetectionTime; }
    public void setLastDetectionTime(LocalDateTime lastDetectionTime) { this.lastDetectionTime = lastDetectionTime; }
    public Duration getDuration() { return duration; }
    public void setDuration(Duration duration) { this.duration = duration; }
    public int getSequenceCloseTimeoutOverrideMinutes() { return sequenceCloseTimeoutOverrideMinutes; }
    public void setSequenceCloseTimeoutOverrideMinutes(int sequenceCloseTimeoutOverrideMinutes) { this.sequenceCloseTimeoutOverrideMinutes = sequenceCloseTimeoutOverrideMinutes; }
    public List<String> getAlerts() { return alerts; }
}
