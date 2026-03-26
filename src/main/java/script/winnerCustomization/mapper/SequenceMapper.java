package script.winnerCustomization.mapper;

import org.springframework.stereotype.Component;
import script.winnerCustomization.dto.SequenceDto;
import script.winnerCustomization.dto.StageDto;
import script.winnerCustomization.model.Sequence;
import script.winnerCustomization.model.Stage;
import script.winnerCustomization.util.DurationFormatter;

import java.util.List;

@Component
public class SequenceMapper {
    public SequenceDto toDto(Sequence sequence) {
        List<StageDto> stages = sequence.getStages().stream().map(this::toDto).toList();
        return new SequenceDto(sequence.getPlate(), sequence.isClosed(), stages);
    }

    private StageDto toDto(Stage stage) {
        return new StageDto(
            stage.getLabel(),
            stage.getInTime() == null ? "" : stage.getInTime().toString(),
            stage.getOutTime() == null ? "" : stage.getOutTime().toString(),
            DurationFormatter.human(stage.getDuration()),
            String.join(", ", stage.getAlerts())
        );
    }
}
