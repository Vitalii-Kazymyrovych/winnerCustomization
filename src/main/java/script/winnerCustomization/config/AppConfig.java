package script.winnerCustomization.config;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private DatabaseConfig database;
    private int sourceRefreshSeconds = 10;
    private String reportsDir = "./reports";
    private MessagingConfig messaging = new MessagingConfig();
    private WorkflowConfig workflow = new WorkflowConfig();
    private List<AlertRuleConfig> alerts = new ArrayList<>();

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
    public List<AlertRuleConfig> getAlerts() { return alerts; }
    public void setAlerts(List<AlertRuleConfig> alerts) { this.alerts = alerts; }
}
