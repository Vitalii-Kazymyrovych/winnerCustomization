package script.winnerCustomization.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Sequence {
    private String plate;
    private boolean closed;
    private LocalDateTime lastDetection;
    private LocalDateTime closedAtUtc;
    private final List<Stage> stages = new ArrayList<>();

    public String getPlate() { return plate; }
    public void setPlate(String plate) { this.plate = plate; }
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }
    public LocalDateTime getLastDetection() { return lastDetection; }
    public void setLastDetection(LocalDateTime lastDetection) { this.lastDetection = lastDetection; }
    public LocalDateTime getClosedAtUtc() { return closedAtUtc; }
    public void setClosedAtUtc(LocalDateTime closedAtUtc) { this.closedAtUtc = closedAtUtc; }
    public List<Stage> getStages() { return stages; }
}
