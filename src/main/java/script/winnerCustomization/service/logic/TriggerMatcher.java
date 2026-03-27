package script.winnerCustomization.service.logic;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import script.winnerCustomization.config.*;
import script.winnerCustomization.model.Detection;
 
import java.util.ArrayList;
import java.util.List;
 
/**
 * Matches detections against configured triggers.
 * Handles direction matching, conflict resolution, and priority.
 */
public class TriggerMatcher {
 
    private static final Logger log = LoggerFactory.getLogger(TriggerMatcher.class);
 
    /**
     * Result of matching a detection against triggers.
     */
    public static class MatchResult {
        public final String stageName;
        public final String stageLabel;
        public final String stageType;    // "real", "transitional", "singleCamera"
        public final String triggerType;  // "in", "out", "singleCamera"
        public final TransitionalStageConfig transitionalConfig; // set only for transitional matches
 
        public MatchResult(String stageName, String stageLabel, String stageType,
                           String triggerType, TransitionalStageConfig transitionalConfig) {
            this.stageName = stageName;
            this.stageLabel = stageLabel;
            this.stageType = stageType;
            this.triggerType = triggerType;
            this.transitionalConfig = transitionalConfig;
        }
 
        @Override
        public String toString() {
            return "Match{" + stageType + "/" + stageName + "/" + triggerType + "}";
        }
    }
 
    private final WorkflowConfig workflow;
 
    // Pre-resolved conflict adjustments
    // Map of (analyticsId -> list of resolved triggers)
    private final List<ResolvedTrigger> resolvedTriggers = new ArrayList<>();
 
    public TriggerMatcher(WorkflowConfig workflow) {
        this.workflow = workflow;
        buildResolvedTriggers();
    }
 
    private static class ResolvedTrigger {
        String stageName;
        String stageLabel;
        String stageType;
        String triggerType;
        int analyticsId;
        Integer direction; // null = any
        int tolerance;
        TransitionalStageConfig transitionalConfig;
    }
 
    private void buildResolvedTriggers() {
        // Add real stage triggers
        for (RealStageConfig stage : workflow.getReal()) {
            for (TriggerConfig trigger : stage.getTriggers()) {
                resolveAndAddTrigger(stage.getName(), stage.getLabel(), "real",
                        trigger.getType(), trigger.getAnalyticsId(), trigger.getDirection(),
                        null, stage.getTriggers());
            }
        }
 
        // Add transitional stage triggers
        for (TransitionalStageConfig stage : workflow.getTransitional()) {
            for (TriggerConfig trigger : stage.getTriggers()) {
                resolveAndAddTrigger(stage.getName(), stage.getLabel(), "transitional",
                        trigger.getType(), trigger.getAnalyticsId(), trigger.getDirection(),
                        stage, stage.getTriggers());
            }
        }
 
        // Add single camera triggers
        for (SingleCameraConfig cam : workflow.getSingleCamera()) {
            ResolvedTrigger rt = new ResolvedTrigger();
            rt.stageName = cam.getName();
            rt.stageLabel = cam.getLabel();
            rt.stageType = "singleCamera";
            rt.triggerType = "singleCamera";
            rt.analyticsId = cam.getAnalyticsId();
            rt.direction = null;
            rt.tolerance = 360; // matches any direction
            rt.transitionalConfig = null;
            resolvedTriggers.add(rt);
        }
 
        log.info("Built {} resolved triggers", resolvedTriggers.size());
    }
 
    private void resolveAndAddTrigger(String stageName, String stageLabel, String stageType,
                                      String triggerType, int analyticsId, Integer direction,
                                      TransitionalStageConfig transitionalConfig,
                                      List<TriggerConfig> allTriggersForStage) {
        ResolvedTrigger rt = new ResolvedTrigger();
        rt.stageName = stageName;
        rt.stageLabel = stageLabel;
        rt.stageType = stageType;
        rt.triggerType = triggerType;
        rt.analyticsId = analyticsId;
        rt.direction = direction;
        rt.transitionalConfig = transitionalConfig;
 
        // Check for in/out conflict on the same analyticsId within the same stage
        int tolerance = DirectionMatcher.DEFAULT_TOLERANCE;
 
        if ("out".equals(triggerType)) {
            // Find matching "in" trigger on same analyticsId
            TriggerConfig inTrigger = findOpposite("in", analyticsId, allTriggersForStage);
            if (inTrigger != null && direction != null && inTrigger.getDirection() != null) {
                if (DirectionMatcher.rangesOverlap(direction, 90, inTrigger.getDirection(), 90)) {
                    // Conflict: expand out to 91 tolerance
                    tolerance = 91;
                    log.info("In/Out conflict on analyticsId={}: out '{}' expanded to 91° tolerance",
                            analyticsId, stageName);
                }
            }
        } else if ("in".equals(triggerType)) {
            TriggerConfig outTrigger = findOpposite("out", analyticsId, allTriggersForStage);
            if (outTrigger != null && direction != null && outTrigger.getDirection() != null) {
                if (DirectionMatcher.rangesOverlap(direction, 90, outTrigger.getDirection(), 90)) {
                    // Conflict: shrink in to 89° tolerance
                    tolerance = 89;
                    log.info("In/Out conflict on analyticsId={}: in '{}' shrunk to 89° tolerance",
                            analyticsId, stageName);
                }
            }
        }
 
        rt.tolerance = tolerance;
        resolvedTriggers.add(rt);
    }
 
    private TriggerConfig findOpposite(String type, int analyticsId, List<TriggerConfig> triggers) {
        for (TriggerConfig t : triggers) {
            if (type.equals(t.getType()) && t.getAnalyticsId() == analyticsId) {
                return t;
            }
        }
        return null;
    }
 
    /**
     * Find all matching triggers for a detection.
     * Returns matches ordered by priority: real > transitional > singleCamera.
     * For stage (non-singleCamera) matches, out triggers have priority over in when both match.
     */
    public List<MatchResult> findMatches(Detection detection) {
        List<MatchResult> results = new ArrayList<>();
 
        for (ResolvedTrigger rt : resolvedTriggers) {
            if (rt.analyticsId != detection.getAnalyticsId()) {
                continue;
            }
 
            // Direction check
            if (rt.direction != null) {
                if (detection.getDirection() == null) {
                    continue; // detection has no direction but trigger requires one
                }
                if (!DirectionMatcher.matches(detection.getDirection(), rt.direction, rt.tolerance)) {
                    continue;
                }
            }
 
            results.add(new MatchResult(rt.stageName, rt.stageLabel, rt.stageType,
                    rt.triggerType, rt.transitionalConfig));
        }
 
        return results;
    }
 
    /**
     * Find the primary (highest priority) stage match for a detection.
     * Returns null if no match. Prioritizes real/transitional over singleCamera,
     * and out over in when both match on the same analyticsId.
     */
    public MatchResult findPrimaryMatch(Detection detection) {
        List<MatchResult> matches = findMatches(detection);
        if (matches.isEmpty()) return null;
 
        // Filter to non-singleCamera first
        MatchResult best = null;
        for (MatchResult m : matches) {
            if ("singleCamera".equals(m.stageType)) continue;
            if (best == null) {
                best = m;
            } else if ("out".equals(m.triggerType) && "in".equals(best.triggerType)) {
                best = m; // out takes priority
            }
        }
        if (best != null) return best;
 
        // Fall back to singleCamera
        for (MatchResult m : matches) {
            if ("singleCamera".equals(m.stageType)) return m;
        }
        return null;
    }
 
    /**
     * Find single camera matches for a detection (processed separately from primary).
     */
    public List<MatchResult> findSingleCameraMatches(Detection detection) {
        List<MatchResult> results = new ArrayList<>();
        for (MatchResult m : findMatches(detection)) {
            if ("singleCamera".equals(m.stageType)) {
                results.add(m);
            }
        }
        return results;
    }
 
    /**
     * Check if a detection matches an alert trigger config.
     */
    public boolean matchesAlertTrigger(Detection detection, AlertTriggerConfig alertTrigger) {
        if (detection.getAnalyticsId() != alertTrigger.getAnalyticsId()) {
            return false;
        }
        if (alertTrigger.getDirection() != null) {
            if (detection.getDirection() == null) {
                return false;
            }
            return DirectionMatcher.matches(detection.getDirection(), alertTrigger.getDirection());
        }
        return true;
    }
}