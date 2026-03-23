package script.winnerCustomization.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;

import java.util.Map;

@Component
public class TelegramNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationSender.class);

    private final RuntimeConfig runtimeConfig;
    private final RestClient restClient;

    public TelegramNotificationSender(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        this.restClient = RestClient.create();
    }

    @Override
    public void send(String message) {
        AppConfig.MessagingConfig messaging = runtimeConfig.get().getMessaging();
        if (messaging == null || !messaging.isEnabled()) {
            log.info("Notifications disabled, skipping Telegram send: {}", message);
            return;
        }
        restClient.post()
                .uri("https://api.telegram.org/bot{token}/sendMessage", messaging.getTelegramBotToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chat_id", messaging.getTelegramChatId(), "text", message))
                .retrieve()
                .toBodilessEntity();
    }
}
