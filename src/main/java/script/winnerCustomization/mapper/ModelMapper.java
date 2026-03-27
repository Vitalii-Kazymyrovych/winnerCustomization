package script.winnerCustomization.mapper;
 
import script.winnerCustomization.dto.AlertDto;
import script.winnerCustomization.dto.SequenceDto;
import script.winnerCustomization.dto.StageDto;
import script.winnerCustomization.model.AlertRecord;
import script.winnerCustomization.model.PlateSequence;
import script.winnerCustomization.model.Stage;
 
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
 
/**
 * Maps between model objects and DTOs.
 */
public class ModelMapper {
 
    public static SequenceDto toDto(PlateSequence seq) {
        SequenceDto dto = new SequenceDto();
        dto.setId(seq.getId());
        dto.setPlateNumber(seq.getPlateNumber());
        dto.setActive(seq.isActive());
        dto.setStartTime(seq.getStartTime());
        dto.setCloseTime(seq.getCloseTime());
        dto.setStages(seq.getStages().stream()
                .map(ModelMapper::toDto)
                .collect(Collectors.toList()));
        return dto;
    }
 
    public static StageDto toDto(Stage stage) {
        StageDto dto = new StageDto();
        dto.setId(stage.getId());
        dto.setName(stage.getName());
        dto.setLabel(stage.getLabel());
        dto.setType(stage.getType());
        dto.setActive(stage.isActive());
        dto.setFull(stage.isFull());
        dto.setTimeout(stage.getTimeout());
        dto.setInTime(stage.getInTime());
        dto.setOutTime(stage.getOutTime());
        dto.setDurationSeconds(stage.getDurationSeconds());
        dto.setPlateNumber(stage.getPlateNumber());
        dto.setAlerts(stage.getAlerts().stream()
                .map(ModelMapper::toDto)
                .collect(Collectors.toList()));
        return dto;
    }
 
    public static AlertDto toDto(AlertRecord alert) {
        AlertDto dto = new AlertDto();
        dto.setId(alert.getId());
        dto.setPlateNumber(alert.getPlateNumber());
        dto.setMessage(alert.getMessage());
        dto.setTimeoutSeconds(alert.getTimeoutSeconds());
        dto.setActive(alert.isActive());
        return dto;
    }
}