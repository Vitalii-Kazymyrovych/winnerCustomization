package script.winnerCustomization.config;
 
import java.util.List;
 
public class WorkflowConfig {
 
    private int sequenceCloseTimeoutMinutes;
    private List<RealStageConfig> real;
    private List<TransitionalStageConfig> transitional;
    private List<SingleCameraConfig> singleCamera;
 
    public int getSequenceCloseTimeoutMinutes() { return sequenceCloseTimeoutMinutes; }
    public void setSequenceCloseTimeoutMinutes(int v) { this.sequenceCloseTimeoutMinutes = v; }
 
    public List<RealStageConfig> getReal() { return real; }
    public void setReal(List<RealStageConfig> real) { this.real = real; }
 
    public List<TransitionalStageConfig> getTransitional() { return transitional; }
    public void setTransitional(List<TransitionalStageConfig> transitional) { this.transitional = transitional; }
 
    public List<SingleCameraConfig> getSingleCamera() { return singleCamera; }
    public void setSingleCamera(List<SingleCameraConfig> singleCamera) { this.singleCamera = singleCamera; }
}