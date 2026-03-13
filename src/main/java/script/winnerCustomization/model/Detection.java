package script.winnerCustomization.model;

import java.time.LocalDateTime;

public record Detection(long id, String plateNumber, int analyticsId, Integer direction, LocalDateTime createdAt) {
}
