package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.repository.SequenceRepository;

import java.util.List;

@Service
public class SequenceStorageService {
    private final SequenceRepository sequenceRepository;

    public SequenceStorageService(SequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    public void initialize() {
        sequenceRepository.initialize();
    }

    public void replaceAll(List<SequenceRecord> records) {
        sequenceRepository.replaceAll(records);
    }
}
