package script.winnerCustomization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;

import static org.assertj.core.api.Assertions.assertThatCode;

class TelegramNotifierTest {
    private final TelegramNotifier telegramNotifier = new TelegramNotifier(new ObjectMapper());

    @Test
    void shouldNotFailWhenNotificationsAreNull() {
        assertThatCode(() -> telegramNotifier.sendIfEnabled(null, "hello")).doesNotThrowAnyException();
    }

    @Test
    void shouldNotFailWhenNotificationsAreDisabled() {
        AppConfig.NotificationsConfig notifications = new AppConfig.NotificationsConfig();
        notifications.setEnabled(false);

        assertThatCode(() -> telegramNotifier.sendIfEnabled(notifications, "hello")).doesNotThrowAnyException();
    }
}
