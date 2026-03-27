package script.winnerCustomization.model;
 
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
 
/**
 * Represents a sequence of stages for a single license plate.
 */
public class PlateSequence {
 
    private long id;
    private String plateNumber;
    private boolean active;
    private LocalDateTime startTime;
    private LocalDateTime closeTime;
    private LocalDateTime lastDetectionTime;
    private List<Stage> stages = new ArrayList<>();
 
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
 
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
 
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
 
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
 
    public LocalDateTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalDateTime closeTime) { this.closeTime = closeTime; }
 
    public LocalDateTime getLastDetectionTime() { return lastDetectionTime; }
    public void setLastDetectionTime(LocalDateTime lastDetectionTime) { this.lastDetectionTime = lastDetectionTime; }
 
    public List<Stage> getStages() { return stages; }
    public void setStages(List<Stage> stages) { this.stages = stages; }
 
    /**
     * Returns the currently active (non-candidate) stage, or null.
     */
    public Stage getActiveStage() {
        for (int i = stages.size() - 1; i >= 0; i--) {
            Stage s = stages.get(i);
            if (s.isActive() && !s.isCandidate()) {
                return s;
            }
        }
        return null;
    }
 
    /**
     * Returns all pending transitional candidates.
     */
    public List<Stage> getCandidates() {
        List<Stage> candidates = new ArrayList<>();
        for (Stage s : stages) {
            if (s.isCandidate() && s.isActive()) {
                candidates.add(s);
            }
        }
        return candidates;
    }
 
    @Override
    public String toString() {
        return "PlateSequence{plate='" + plateNumber + "', active=" + active +
                ", stages=" + stages.size() + "}";
    }
}