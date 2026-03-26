package script.winnerCustomization.model;

import java.time.LocalDateTime;

public record Detection(long id, String plate, int analyticsId, Integer direction, LocalDateTime createdAtUtc) {
}
