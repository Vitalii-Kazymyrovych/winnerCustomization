package script.winnerCustomization.config;
 
public class AlertConfig {
 
    private AlertTriggerConfig trigger;
    private String message;
    private int sendTimeOutMinutes;
 
    public AlertTriggerConfig getTrigger() { return trigger; }
    public void setTrigger(AlertTriggerConfig trigger) { this.trigger = trigger; }
 
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
 
    public int getSendTimeOutMinutes() { return sendTimeOutMinutes; }
    public void setSendTimeOutMinutes(int sendTimeOutMinutes) { this.sendTimeOutMinutes = sendTimeOutMinutes; }
}