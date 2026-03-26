package script.winnerCustomization.config;

public class AlertRuleConfig {
    private TriggerConfig trigger;
    private String message;
    private int sendTimeOutMinutes;

    public TriggerConfig getTrigger() { return trigger; }
    public void setTrigger(TriggerConfig trigger) { this.trigger = trigger; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public int getSendTimeOutMinutes() { return sendTimeOutMinutes; }
    public void setSendTimeOutMinutes(int sendTimeOutMinutes) { this.sendTimeOutMinutes = sendTimeOutMinutes; }
}
