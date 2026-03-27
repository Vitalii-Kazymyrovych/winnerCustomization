package script.winnerCustomization.service.logic;
 
/**
 * Manages the periodic polling of source detections and state updates.
 */
public interface SchedulerService {
 
    void startPolling();
 
    void stopPolling();
}