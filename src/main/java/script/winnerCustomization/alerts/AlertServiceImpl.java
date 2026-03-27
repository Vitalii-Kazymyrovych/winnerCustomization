package script.winnerCustomization.alerts;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.model.AlertRecord;
 
@Service
public class AlertServiceImpl implements AlertService {
 
    private static final Logger log = LoggerFactory.getLogger(AlertServiceImpl.class);
 
    private final ConfigLoader configLoader;
    private final TelegramSender telegramSender;
 
    public AlertServiceImpl(ConfigLoader configLoader, TelegramSender telegramSender) {
        this.configLoader = configLoader;
        this.telegramSender = telegramSender;
    }
 
    @Override
    public void sendAlert(AlertRecord alert) {
        String text = alert.getPlateNumber() + ": " + alert.getMessage();
 
        if (configLoader.getConfig().getMessaging().isEnabled()) {
            log.info("Sending alert via Telegram: {}", text);
            telegramSender.sendMessage(text);
        } else {
            log.info("ALERT (messaging disabled): {}", text);
        }
    }
}