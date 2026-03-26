package script.winnerCustomization.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import script.winnerCustomization.config.AppConfig;

import java.util.Map;

@Component
public class TelegramAlertSender implements AlertSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramAlertSender.class);
    private final AppConfig appConfig;
    private final RestClient restClient = RestClient.create();

    public TelegramAlertSender(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public void send(String text) {
        if (!appConfig.getMessaging().isEnabled()) {
            log.info("Alert: {}", text);
            return;
        }
        String url = "https://api.telegram.org/bot" + appConfig.getMessaging().getTelegramBotToken() + "/sendMessage";
        restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("chat_id", appConfig.getMessaging().getTelegramChatId(), "text", text))
            .retrieve()
            .toBodilessEntity();
        log.info("Telegram alert sent");
    }
}
