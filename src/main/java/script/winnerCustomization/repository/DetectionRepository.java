package script.winnerCustomization.repository;

import script.winnerCustomization.model.Detection;

import java.time.LocalDateTime;
import java.util.List;

public interface DetectionRepository {
    List<Detection> findAll();
    List<Detection> findBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive);
}
