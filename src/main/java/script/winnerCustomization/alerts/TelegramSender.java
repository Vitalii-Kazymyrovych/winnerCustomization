package script.winnerCustomization.alerts;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.config.MessagingConfig;
 
import java.util.HashMap;
import java.util.Map;
 
@Component
public class TelegramSender {
 
    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);
    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot%s/sendMessage";
 
    private final ConfigLoader configLoader;
    private final RestTemplate restTemplate = new RestTemplate();
 
    public TelegramSender(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }
 
    public void sendMessage(String text) {
        MessagingConfig mc = configLoader.getConfig().getMessaging();
        String url = String.format(TELEGRAM_API_URL, mc.getTelegramBotToken());
 
        Map<String, String> body = new HashMap<>();
        body.put("chat_id", mc.getTelegramChatId());
        body.put("text", text);
 
        try {
            restTemplate.postForEntity(url, body, String.class);
            log.info("Telegram message sent successfully");
        } catch (Exception e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }
}