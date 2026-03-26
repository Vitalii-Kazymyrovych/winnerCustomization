package script.winnerCustomization.repository;

import org.springframework.stereotype.Repository;
import script.winnerCustomization.model.Alert;
import script.winnerCustomization.model.Sequence;

import java.util.ArrayList;
import java.util.List;

@Repository
public class InMemorySequenceStateRepository implements SequenceStateRepository {
    private final List<Sequence> sequences = new ArrayList<>();
    private final List<Alert> alerts = new ArrayList<>();

    @Override
    public synchronized void replaceAll(List<Sequence> newSequences, List<Alert> newAlerts) {
        sequences.clear();
        sequences.addAll(newSequences);
        alerts.clear();
        alerts.addAll(newAlerts);
    }

    @Override
    public synchronized List<Sequence> findAllSequences() {
        return new ArrayList<>(sequences);
    }

    @Override
    public synchronized List<Alert> findAllAlerts() {
        return new ArrayList<>(alerts);
    }
}
