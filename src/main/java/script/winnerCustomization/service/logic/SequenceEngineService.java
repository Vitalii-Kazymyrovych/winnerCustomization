package script.winnerCustomization.service.logic;

import script.winnerCustomization.model.Alert;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.Sequence;

import java.time.LocalDateTime;
import java.util.List;

public interface SequenceEngineService {
    EngineSnapshot rebuild(List<Detection> detections, LocalDateTime nowUtc);
    EngineSnapshot applyIncremental(List<Sequence> currentSequences,
                                    List<Alert> currentAlerts,
                                    List<Detection> newDetections,
                                    LocalDateTime previousTickUtc,
                                    LocalDateTime nowUtc);

    record EngineSnapshot(List<Sequence> sequences, List<Alert> alerts, LocalDateTime lastProcessedTimestamp) {
    }
}
