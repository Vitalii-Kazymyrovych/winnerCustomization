package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Service
public class TelegramNotifier {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    public TelegramNotifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void sendIfEnabled(AppConfig.MessagingConfig messaging, String text) {
        if (messaging == null || !messaging.isEnabled()) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of("chat_id", messaging.getTelegramChatId(), "text", text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + messaging.getTelegramBotToken() + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception exception) {
            log.warn("Failed to send Telegram notification: {}", exception.getMessage());
        }
    }
}
