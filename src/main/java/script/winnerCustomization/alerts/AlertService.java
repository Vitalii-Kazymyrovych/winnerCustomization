package script.winnerCustomization.alerts;
 
import script.winnerCustomization.model.AlertRecord;
 
/**
 * Service responsible for sending alerts.
 */
public interface AlertService {
 
    void sendAlert(AlertRecord alert);
}