package script.winnerCustomization.config;
 
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
 
public class AppConfig {
 
    private DatabaseConfig database;
    private int sourceRefreshSeconds;
    private String reportsDir;
    private MessagingConfig messaging;
    private WorkflowConfig workflow;
    private List<AlertConfig> alerts;
 
    public DatabaseConfig getDatabase() { return database; }
    public void setDatabase(DatabaseConfig database) { this.database = database; }
 
    public int getSourceRefreshSeconds() { return sourceRefreshSeconds; }
    public void setSourceRefreshSeconds(int sourceRefreshSeconds) { this.sourceRefreshSeconds = sourceRefreshSeconds; }
 
    public String getReportsDir() { return reportsDir; }
    public void setReportsDir(String reportsDir) { this.reportsDir = reportsDir; }
 
    public MessagingConfig getMessaging() { return messaging; }
    public void setMessaging(MessagingConfig messaging) { this.messaging = messaging; }
 
    public WorkflowConfig getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowConfig workflow) { this.workflow = workflow; }
 
    public List<AlertConfig> getAlerts() { return alerts; }
    public void setAlerts(List<AlertConfig> alerts) { this.alerts = alerts; }
}