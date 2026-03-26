package script.winnerCustomization.config;

public class MessagingConfig {
    private boolean enabled;
    private String telegramBotToken;
    private String telegramChatId;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTelegramBotToken() { return telegramBotToken; }
    public void setTelegramBotToken(String telegramBotToken) { this.telegramBotToken = telegramBotToken; }
    public String getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(String telegramChatId) { this.telegramChatId = telegramChatId; }
}
