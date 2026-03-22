package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.repository.DetectionRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DetectionService {
    private final DetectionRepository detectionRepository;

    public DetectionService(DetectionRepository detectionRepository) {
        this.detectionRepository = detectionRepository;
    }

    public List<Detection> loadAllDetections() {
        return detectionRepository.findAll();
    }

    public List<Detection> loadDetectionsBetween(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return detectionRepository.findBetween(fromInclusive, toExclusive);
    }
}
