package script.winnerCustomization.repository;

import script.winnerCustomization.model.Detection;

import java.time.LocalDateTime;
import java.util.List;

public interface SourceDetectionRepository {
    List<Detection> findAll();
    List<Detection> findNewerThan(LocalDateTime timestampUtc);
}
