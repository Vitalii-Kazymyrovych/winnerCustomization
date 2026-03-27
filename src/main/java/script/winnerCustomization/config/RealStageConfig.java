package script.winnerCustomization.config;
 
import java.util.List;
 
public class RealStageConfig {
 
    private String name;
    private String label;
    private List<TriggerConfig> triggers;
 
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
 
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
 
    public List<TriggerConfig> getTriggers() { return triggers; }
    public void setTriggers(List<TriggerConfig> triggers) { this.triggers = triggers; }
}