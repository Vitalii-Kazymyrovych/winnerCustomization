package script.winnerCustomization.config;
 
import java.util.List;
 
public class TransitionalStageConfig {
 
    private String name;
    private String label;
    private List<TriggerConfig> triggers;
    private List<String> allowedAfter;
    private int candidateTimeoutMinutes;
    private int sequenceCloseTimeoutOverrideMinutes;
 
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
 
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
 
    public List<TriggerConfig> getTriggers() { return triggers; }
    public void setTriggers(List<TriggerConfig> triggers) { this.triggers = triggers; }
 
    public List<String> getAllowedAfter() { return allowedAfter; }
    public void setAllowedAfter(List<String> allowedAfter) { this.allowedAfter = allowedAfter; }
 
    public int getCandidateTimeoutMinutes() { return candidateTimeoutMinutes; }
    public void setCandidateTimeoutMinutes(int v) { this.candidateTimeoutMinutes = v; }
 
    public int getSequenceCloseTimeoutOverrideMinutes() { return sequenceCloseTimeoutOverrideMinutes; }
    public void setSequenceCloseTimeoutOverrideMinutes(int v) { this.sequenceCloseTimeoutOverrideMinutes = v; }
}