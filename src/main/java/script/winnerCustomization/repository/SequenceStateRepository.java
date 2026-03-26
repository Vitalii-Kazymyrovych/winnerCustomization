package script.winnerCustomization.repository;

import script.winnerCustomization.model.Alert;
import script.winnerCustomization.model.Sequence;

import java.util.List;

public interface SequenceStateRepository {
    void replaceAll(List<Sequence> sequences, List<Alert> alerts);
    List<Sequence> findAllSequences();
    List<Alert> findAllAlerts();
}
