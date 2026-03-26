package script.winnerCustomization.config;

import java.util.ArrayList;
import java.util.List;

public class WorkflowConfig {
    private int sequenceCloseTimeoutMinutes = 2880;
    private List<StageRuleConfig> real = new ArrayList<>();
    private List<StageRuleConfig> transitional = new ArrayList<>();
    private List<StageRuleConfig> singleCamera = new ArrayList<>();

    public int getSequenceCloseTimeoutMinutes() { return sequenceCloseTimeoutMinutes; }
    public void setSequenceCloseTimeoutMinutes(int sequenceCloseTimeoutMinutes) { this.sequenceCloseTimeoutMinutes = sequenceCloseTimeoutMinutes; }
    public List<StageRuleConfig> getReal() { return real; }
    public void setReal(List<StageRuleConfig> real) { this.real = real; }
    public List<StageRuleConfig> getTransitional() { return transitional; }
    public void setTransitional(List<StageRuleConfig> transitional) { this.transitional = transitional; }
    public List<StageRuleConfig> getSingleCamera() { return singleCamera; }
    public void setSingleCamera(List<StageRuleConfig> singleCamera) { this.singleCamera = singleCamera; }
}
