package script.winnerCustomization.dto;
 
import java.time.LocalDateTime;
import java.util.List;
 
public class SequenceDto {
 
    private long id;
    private String plateNumber;
    private boolean active;
    private LocalDateTime startTime;
    private LocalDateTime closeTime;
    private List<StageDto> stages;
 
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
 
    public List<StageDto> getStages() { return stages; }
    public void setStages(List<StageDto> stages) { this.stages = stages; }
}