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
    private Duration duration;
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
    public Duration getDuration() { return duration; }
    public void setDuration(Duration duration) { this.duration = duration; }
    public List<String> getAlerts() { return alerts; }
}
