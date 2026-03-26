package script.winnerCustomization.dto;

import java.util.List;

public record SequenceDto(String plate, boolean closed, List<StageDto> stages) {
}
