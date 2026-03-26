package script.winnerCustomization.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.dto.SequenceDto;
import script.winnerCustomization.mapper.SequenceMapper;
import script.winnerCustomization.repository.SequenceStateRepository;

import java.util.List;

@RestController
@RequestMapping("/api/sequences")
public class SequenceController {
    private final SequenceStateRepository sequenceStateRepository;
    private final SequenceMapper sequenceMapper;

    public SequenceController(SequenceStateRepository sequenceStateRepository, SequenceMapper sequenceMapper) {
        this.sequenceStateRepository = sequenceStateRepository;
        this.sequenceMapper = sequenceMapper;
    }

    @GetMapping
    public List<SequenceDto> all() {
        return sequenceStateRepository.findAllSequences().stream().map(sequenceMapper::toDto).toList();
    }
}
