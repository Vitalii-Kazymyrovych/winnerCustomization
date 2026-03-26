package script.winnerCustomization.config;

import java.util.ArrayList;
import java.util.List;

public class StageRuleConfig {
    private String name;
    private String label;
    private java.util.List<TriggerConfig> triggers = new ArrayList<>();
    private java.util.List<String> allowedAfter = new ArrayList<>();
    private int candidateTimeoutMinutes;
    private int sequenceCloseTimeoutOverrideMinutes;
    private Integer analyticsId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public List<TriggerConfig> getTriggers() { return triggers; }
    public void setTriggers(List<TriggerConfig> triggers) { this.triggers = triggers; }
    public List<String> getAllowedAfter() { return allowedAfter; }
    public void setAllowedAfter(List<String> allowedAfter) { this.allowedAfter = allowedAfter; }
    public int getCandidateTimeoutMinutes() { return candidateTimeoutMinutes; }
    public void setCandidateTimeoutMinutes(int candidateTimeoutMinutes) { this.candidateTimeoutMinutes = candidateTimeoutMinutes; }
    public int getSequenceCloseTimeoutOverrideMinutes() { return sequenceCloseTimeoutOverrideMinutes; }
    public void setSequenceCloseTimeoutOverrideMinutes(int sequenceCloseTimeoutOverrideMinutes) { this.sequenceCloseTimeoutOverrideMinutes = sequenceCloseTimeoutOverrideMinutes; }
    public Integer getAnalyticsId() { return analyticsId; }
    public void setAnalyticsId(Integer analyticsId) { this.analyticsId = analyticsId; }
}
