package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import script.winnerCustomization.model.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Service
public class TelegramNotifier {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    public TelegramNotifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void sendIfEnabled(AppConfig.NotificationsConfig notifications, String text) {
        if (notifications == null || !notifications.isEnabled()) {
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + notifications.getTelegramBotToken() + "/sendMessage";
            String payload = objectMapper.writeValueAsString(Map.of(
                    "chat_id", notifications.getTelegramChatId(),
                    "text", text
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }
}
