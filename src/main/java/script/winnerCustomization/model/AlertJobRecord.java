package script.winnerCustomization.model;

import java.time.LocalDateTime;

public record AlertJobRecord(
        long id,
        String plateNumber,
        AlertJobType type,
        LocalDateTime triggerAt,
        LocalDateTime dueAt,
        String message
) {
}
