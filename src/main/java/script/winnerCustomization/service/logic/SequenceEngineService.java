package script.winnerCustomization.service.logic;
 
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.PlateSequence;
 
import java.util.List;
 
/**
 * Core engine that processes ALPR detections and builds sequences of stages.
 */
public interface SequenceEngineService {
 
    /**
     * Process a batch of detections (sorted by created_at ascending).
     * Builds/updates sequences, stages, alerts in memory.
     */
    void processDetections(List<Detection> detections);
 
    /**
     * Perform periodic maintenance: decrement timeouts, materialize candidates,
     * fire alerts, close timed-out sequences, recalculate durations.
     */
    void performMaintenance(int elapsedSeconds);
 
    /**
     * Get all sequences (active and closed).
     */
    List<PlateSequence> getAllSequences();
 
    /**
     * Reset all state (used on restart).
     */
    void reset();
}