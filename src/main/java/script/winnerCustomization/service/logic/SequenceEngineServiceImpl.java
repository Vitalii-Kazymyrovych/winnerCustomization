package script.winnerCustomization.service.logic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import script.winnerCustomization.config.*;
import script.winnerCustomization.model.AlertRecord;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.PlateSequence;
import script.winnerCustomization.model.Stage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SequenceEngineServiceImpl implements SequenceEngineService {

    private static final Logger log = LoggerFactory.getLogger(SequenceEngineServiceImpl.class);

    private final ConfigLoader configLoader;
    private TriggerMatcher triggerMatcher;

    // State: plate -> active sequence
    private final Map<String, PlateSequence> activeSequences = new LinkedHashMap<>();
    // State: all closed sequences
    private final List<PlateSequence> closedSequences = new ArrayList<>();
    // All alerts (across all sequences)
    private final List<AlertRecord> allAlerts = new ArrayList<>();

    // Alerts to send (populated during maintenance)
    private final List<AlertRecord> pendingAlertSends = new ArrayList<>();

    // Sequences that closed since the last clearNewlyClosedSequences() call
    private final List<PlateSequence> newlyClosedSequences = new ArrayList<>();

    public SequenceEngineServiceImpl(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    private TriggerMatcher getTriggerMatcher() {
        if (triggerMatcher == null) {
            triggerMatcher = new TriggerMatcher(configLoader.getConfig().getWorkflow());
        }
        return triggerMatcher;
    }

    @Override
    public void reset() {
        activeSequences.clear();
        closedSequences.clear();
        allAlerts.clear();
        pendingAlertSends.clear();
        newlyClosedSequences.clear();
        triggerMatcher = null;
        log.info("Sequence engine state reset");
    }

    @Override
    public void processDetections(List<Detection> detections) {
        TriggerMatcher matcher = getTriggerMatcher();

        for (Detection detection : detections) {
            processOneDetection(detection, matcher);
        }
    }

    private void processOneDetection(Detection detection, TriggerMatcher matcher) {
        String plate = detection.getPlateNumber();
        if (plate == null || plate.isBlank()) return;

        // Bug 1 fix (historical + runtime): close the active sequence if the gap since the
        // last detection exceeds the configured timeout before processing the new detection.
        checkAndCloseForDetectionGap(plate, detection.getCreatedAt());

        // Find primary (real/transitional) match
        TriggerMatcher.MatchResult primaryMatch = matcher.findPrimaryMatch(detection);

        // Invalidate pending candidates for any detection, except a repeated out for the same active stage
        PlateSequence seqBeforeProcess = activeSequences.get(plate);
        if (seqBeforeProcess != null) {
            Stage activeStageNow = seqBeforeProcess.getActiveStage();
            boolean isRepeatOutSameStage = primaryMatch != null
                    && "out".equals(primaryMatch.triggerType)
                    && activeStageNow != null
                    && activeStageNow.getName().equals(primaryMatch.stageName);
            if (!isRepeatOutSameStage) {
                invalidateCandidates(seqBeforeProcess, detection.getCreatedAt());
            }
        }

        if (primaryMatch != null && !"singleCamera".equals(primaryMatch.stageType)) {
            processStageMatch(detection, primaryMatch);
        }

        // Process single camera matches separately
        List<TriggerMatcher.MatchResult> singleCameraMatches = matcher.findSingleCameraMatches(detection);
        for (TriggerMatcher.MatchResult scMatch : singleCameraMatches) {
            processSingleCameraMatch(detection, scMatch);
        }

        // Process alert triggers
        processAlertTriggers(detection, matcher);

        // Update last detection time only when the detection matched a known trigger
        if (primaryMatch != null || !singleCameraMatches.isEmpty()) {
            PlateSequence seq = activeSequences.get(plate);
            if (seq != null) {
                seq.setLastDetectionTime(detection.getCreatedAt());
            }
        }
    }

    private void processStageMatch(Detection detection, TriggerMatcher.MatchResult match) {
        String plate = detection.getPlateNumber();
        PlateSequence seq = activeSequences.get(plate);

        if ("in".equals(match.triggerType)) {
            handleInTrigger(detection, match, seq);
        } else if ("out".equals(match.triggerType)) {
            handleOutTrigger(detection, match, seq);
        }
    }

    // ========== IN TRIGGER ==========

    private void handleInTrigger(Detection detection, TriggerMatcher.MatchResult match, PlateSequence seq) {
        String plate = detection.getPlateNumber();

        if (seq == null) {
            // Start a new sequence
            seq = createNewSequence(plate, detection.getCreatedAt());
        }

        // Transitional in-trigger: create a candidate, do NOT close the previous active stage
        if ("transitional".equals(match.stageType) && match.transitionalConfig != null) {
            // Dedup: skip if a pending candidate with the same name already exists
            boolean alreadyExists = seq.getStages().stream()
                    .anyMatch(s -> s.isCandidate() && s.isActive() && s.getName().equals(match.stageName));
            if (alreadyExists) {
                log.debug("Dedup: transitional candidate '{}' already exists for plate={}",
                        match.stageName, plate);
                return;
            }
            TransitionalStageConfig tc = match.transitionalConfig;
            Stage candidate = new Stage();
            candidate.setName(match.stageName);
            candidate.setLabel(match.stageLabel);
            candidate.setType("transitional");
            candidate.setActive(true);
            candidate.setCandidate(true);
            candidate.setFull(false);
            candidate.setInTime(detection.getCreatedAt());
            candidate.setTimeout(tc.getCandidateTimeoutMinutes() * 60 + 1);
            candidate.setPlateNumber(plate);
            candidate.setSequenceCloseTimeoutOverrideMinutes(tc.getSequenceCloseTimeoutOverrideMinutes());
            seq.getStages().add(candidate);
            log.debug("Created transitional candidate '{}' for plate={} via in-trigger at {}",
                    match.stageName, plate, detection.getCreatedAt());
            return;
        }

        Stage activeStage = seq.getActiveStage();

        // Deduplication: if same stage is already active with an in event, ignore
        if (activeStage != null && activeStage.getName().equals(match.stageName)
                && activeStage.getInTime() != null
                && activeStage.getOutTime() == null) {
            log.debug("Dedup: in trigger for already-active stage '{}' plate={}", match.stageName, plate);
            return;
        }

        // Invalidate any pending transitional candidates
        invalidateCandidates(seq, detection.getCreatedAt());

        // Close previous active stage
        if (activeStage != null) {
            if (activeStage.getOutTime() != null) {
                // Stage has outTime already — close using its current outTime
                closeActiveStage(activeStage, null);
            } else {
                // No outTime — close with new stage's inTime - 1 second
                closeActiveStage(activeStage, detection.getCreatedAt().minusSeconds(1));
            }
        }

        // Open new stage
        Stage newStage = new Stage();
        newStage.setName(match.stageName);
        newStage.setLabel(match.stageLabel);
        newStage.setType(match.stageType);
        newStage.setActive(true);
        newStage.setFull(true);
        newStage.setInTime(detection.getCreatedAt());
        newStage.setPlateNumber(plate);
        newStage.setTimeout(0);

        seq.getStages().add(newStage);
        log.debug("Opened {} stage '{}' for plate={} at {}", match.stageType, match.stageName,
                plate, detection.getCreatedAt());
    }

    // ========== OUT TRIGGER ==========

    private void handleOutTrigger(Detection detection, TriggerMatcher.MatchResult match, PlateSequence seq) {
        String plate = detection.getPlateNumber();

        if (seq == null) {
            // Cold-start rule: first detection is an out trigger
            seq = createNewSequence(plate, detection.getCreatedAt());
            Stage partial = createPartialStage(match, plate, detection.getCreatedAt());
            seq.getStages().add(partial);
            log.debug("Cold-start partial stage '{}' for plate={}", match.stageName, plate);
            return;
        }

        Stage activeStage = seq.getActiveStage();

        // Check if this out is for the same stage that is active
        if (activeStage != null && activeStage.getName().equals(match.stageName)) {
            if (!activeStage.isFull() && activeStage.getInTime() == null) {
                // Partial stage (cold-start): promote to full by shifting times
                activeStage.setInTime(activeStage.getOutTime());
                activeStage.setOutTime(detection.getCreatedAt());
                activeStage.setFull(true);
                log.debug("Promoted partial stage '{}' plate={} to full: inTime={} outTime={}",
                        match.stageName, plate, activeStage.getInTime(), activeStage.getOutTime());
            } else {
                // Normal: overwrite outTime
                activeStage.setOutTime(detection.getCreatedAt());
                log.debug("Updated outTime for active stage '{}' plate={} to {}",
                        match.stageName, plate, detection.getCreatedAt());
            }

            // Check for transitional auto-start candidate creation
            checkTransitionalAutoStart(seq, activeStage, detection.getCreatedAt());

            // Reset any existing candidates for this stage's allowedAfter triggers
            resetCandidateOnStageOut(seq, activeStage, detection.getCreatedAt());
            return;
        }

        // Out trigger for a different stage than the active one
        if (activeStage != null) {
            // Invalidate candidates
            invalidateCandidates(seq, detection.getCreatedAt());
            if (activeStage.getOutTime() != null) {
                // Active stage has outTime -> close it using its current outTime
                closeActiveStage(activeStage, null);
            } else {
                // Active stage has no outTime -> close with detection - 1 second
                closeActiveStage(activeStage, detection.getCreatedAt().minusSeconds(1));
            }
            if (activeStage.getOutTime() != null) {
                checkTransitionalAutoStart(seq, activeStage, activeStage.getOutTime());
            }
        }

        // Create partial stage for the new out trigger
        Stage partial = createPartialStage(match, plate, detection.getCreatedAt());
        if (partial != null) {
            seq.getStages().add(partial);
        }
        log.debug("Partial stage '{}' for plate={}", match.stageName, plate);
    }

    // ========== SINGLE CAMERA ==========

    private void processSingleCameraMatch(Detection detection, TriggerMatcher.MatchResult match) {
        String plate = detection.getPlateNumber();
        PlateSequence seq = activeSequences.get(plate);

        if (seq == null) {
            seq = createNewSequence(plate, detection.getCreatedAt());
        }

        Stage activeStage = seq.getActiveStage();

        // Check if same single camera stage is already active
        if (activeStage != null && activeStage.getName().equals(match.stageName)
                && "singleCamera".equals(activeStage.getType())) {
            // Update outTime with each new detection on the same camera
            activeStage.setOutTime(detection.getCreatedAt());
            return;
        }

        // If there's no active real/transitional match for this detection,
        // and we have a single camera match, open the single camera stage
        TriggerMatcher.MatchResult primary = getTriggerMatcher().findPrimaryMatch(detection);
        if (primary != null && !"singleCamera".equals(primary.stageType)) {
            // A real/transitional match takes precedence; single camera only updates if already active
            return;
        }

        // Invalidate candidates
        invalidateCandidates(seq, detection.getCreatedAt());

        // Close previous active stage
        if (activeStage != null) {
            closeActiveStageForSingleCamera(activeStage, detection.getCreatedAt());
        }

        // Open new single camera stage
        Stage scStage = new Stage();
        scStage.setName(match.stageName);
        scStage.setLabel(match.stageLabel);
        scStage.setType("singleCamera");
        scStage.setActive(true);
        scStage.setFull(true);
        scStage.setInTime(detection.getCreatedAt());
        scStage.setOutTime(detection.getCreatedAt()); // first = last initially
        scStage.setPlateNumber(plate);
        scStage.setTimeout(0);
        seq.getStages().add(scStage);

        log.debug("Opened singleCamera stage '{}' for plate={}", match.stageName, plate);
    }

    // ========== TRANSITIONAL AUTO-START ==========

    private void checkTransitionalAutoStart(PlateSequence seq, Stage closingStage,
                                            LocalDateTime outTime) {
        WorkflowConfig wf = configLoader.getConfig().getWorkflow();
        for (TransitionalStageConfig tc : wf.getTransitional()) {
            if (tc.getAllowedAfter().contains(closingStage.getName())) {
                // Check if a candidate already exists for this transitional
                boolean alreadyExists = seq.getStages().stream()
                        .anyMatch(s -> s.isCandidate() && s.isActive() && s.getName().equals(tc.getName()));
                if (!alreadyExists) {
                    createTransitionalCandidate(seq, tc, outTime);
                }
            }
        }
    }

    private void createTransitionalCandidate(PlateSequence seq, TransitionalStageConfig tc,
                                             LocalDateTime afterOutTime) {
        Stage candidate = new Stage();
        candidate.setName(tc.getName());
        candidate.setLabel(tc.getLabel());
        candidate.setType("transitional");
        candidate.setActive(true);
        candidate.setCandidate(true);
        candidate.setFull(false);
        candidate.setInTime(afterOutTime.plusSeconds(1));
        // +1 so that duration at materialization = candidateTimeoutMinutes * 60 seconds exactly
        // (inTime = outTime+1s, so without the +1 the stage would be 1 second too short)
        candidate.setTimeout(tc.getCandidateTimeoutMinutes() * 60 + 1); // store as seconds
        candidate.setPlateNumber(seq.getPlateNumber());
        candidate.setSequenceCloseTimeoutOverrideMinutes(tc.getSequenceCloseTimeoutOverrideMinutes());
        seq.getStages().add(candidate);

        log.debug("Created transitional candidate '{}' for plate={}, timeout={}min",
                tc.getName(), seq.getPlateNumber(), tc.getCandidateTimeoutMinutes());
    }

    private void resetCandidateOnStageOut(PlateSequence seq, Stage activeStage,
                                          LocalDateTime outTime) {
        WorkflowConfig wf = configLoader.getConfig().getWorkflow();
        for (TransitionalStageConfig tc : wf.getTransitional()) {
            if (tc.getAllowedAfter().contains(activeStage.getName())) {
                // Find existing candidate and reset its timeout
                for (Stage s : seq.getStages()) {
                    if (s.isCandidate() && s.isActive() && s.getName().equals(tc.getName())) {
                        s.setTimeout(tc.getCandidateTimeoutMinutes() * 60 + 1);
                        s.setInTime(outTime.plusSeconds(1));
                        log.debug("Reset candidate '{}' timeout for plate={}", tc.getName(),
                                seq.getPlateNumber());
                    }
                }
            }
        }
    }

    private void invalidateCandidates(PlateSequence seq, LocalDateTime detectionTime) {
        Iterator<Stage> it = seq.getStages().iterator();
        while (it.hasNext()) {
            Stage s = it.next();
            if (s.isCandidate() && s.isActive()) {
                it.remove();
                log.debug("Invalidated candidate '{}' for plate={}", s.getName(), seq.getPlateNumber());
            }
        }
    }

    // ========== STAGE CLOSING ==========

    private void closeActiveStage(Stage stage, LocalDateTime closeTime) {
        stage.setActive(false);
        if (closeTime != null) {
            stage.setOutTime(closeTime);
        }
        if (stage.getInTime() != null && stage.getOutTime() != null) {
            stage.setDurationSeconds(Duration.between(stage.getInTime(), stage.getOutTime()).getSeconds());
        }
        log.debug("Closed stage '{}' plate={} outTime={}", stage.getName(),
                stage.getPlateNumber(), stage.getOutTime());
    }

    private void closeActiveStageForSingleCamera(Stage stage, LocalDateTime newStageTime) {
        stage.setActive(false);
        if ("singleCamera".equals(stage.getType())) {
            // Single camera: outTime is already set to last detection
        } else {
            // Real/transitional stage being closed by single camera
            if (stage.getOutTime() == null) {
                stage.setOutTime(newStageTime.minusSeconds(1));
            }
        }
        if (stage.getInTime() != null && stage.getOutTime() != null) {
            stage.setDurationSeconds(Duration.between(stage.getInTime(), stage.getOutTime()).getSeconds());
        }
    }

    // ========== PARTIAL STAGE HELPERS ==========

    private Stage createPartialStage(TriggerMatcher.MatchResult match, String plate,
                                     LocalDateTime outTime) {
        // Check if the last stage for this plate (in the same sequence) is a partial for the same stage name
        PlateSequence seq = activeSequences.get(plate);
        if (seq != null) {
            Stage lastPartial = findLastPartialForStage(seq, match.stageName);
            if (lastPartial != null && lastPartial.isActive() && !lastPartial.isFull()
                    && lastPartial.getInTime() == null) {
                // Second out for same partial: promote to full
                lastPartial.setInTime(lastPartial.getOutTime());
                lastPartial.setOutTime(outTime);
                lastPartial.setFull(true);
                log.debug("Promoted partial stage '{}' to full for plate={}", match.stageName, plate);
                return null; // signal that we modified existing, don't add new
            }
        }

        Stage partial = new Stage();
        partial.setName(match.stageName);
        partial.setLabel(match.stageLabel);
        partial.setType(match.stageType);
        partial.setActive(true);
        partial.setFull(false);
        partial.setInTime(null);
        partial.setOutTime(outTime);
        partial.setPlateNumber(plate);
        partial.setTimeout(0);

        if ("transitional".equals(match.stageType) && match.transitionalConfig != null) {
            partial.setSequenceCloseTimeoutOverrideMinutes(
                    match.transitionalConfig.getSequenceCloseTimeoutOverrideMinutes());
        }

        return partial;
    }

    private Stage findLastPartialForStage(PlateSequence seq, String stageName) {
        for (int i = seq.getStages().size() - 1; i >= 0; i--) {
            Stage s = seq.getStages().get(i);
            if (s.getName().equals(stageName) && s.isActive() && !s.isFull()) {
                return s;
            }
        }
        return null;
    }

    // ========== SEQUENCE MANAGEMENT ==========

    private PlateSequence createNewSequence(String plate, LocalDateTime startTime) {
        PlateSequence seq = new PlateSequence();
        seq.setPlateNumber(plate);
        seq.setActive(true);
        seq.setStartTime(startTime);
        seq.setLastDetectionTime(startTime);
        activeSequences.put(plate, seq);
        log.debug("Created new sequence for plate={} at {}", plate, startTime);
        return seq;
    }

    // ========== ALERT PROCESSING ==========

    private void processAlertTriggers(Detection detection, TriggerMatcher matcher) {
        String plate = detection.getPlateNumber();
        PlateSequence seq = activeSequences.get(plate);
        if (seq == null) return;

        Stage activeStage = seq.getActiveStage();
        if (activeStage == null) return;

        List<AlertConfig> alertConfigs = configLoader.getConfig().getAlerts();
        for (AlertConfig ac : alertConfigs) {
            if (matcher.matchesAlertTrigger(detection, ac.getTrigger())) {
                // Only create alert if there's an active stage for this plate on this analyticsId
                handleAlertMatch(seq, activeStage, detection, ac);
            }
        }

        // Check if this detection is on a DIFFERENT analyticsId than any active alert
        // If so, deactivate those alerts
        deactivateAlertsOnDifferentCamera(plate, detection.getAnalyticsId());
    }

    private void handleAlertMatch(PlateSequence seq, Stage activeStage,
                                  Detection detection, AlertConfig ac) {
        String plate = detection.getPlateNumber();
        int triggerAnalyticsId = ac.getTrigger().getAnalyticsId();

        // Check if an active alert already exists for this plate + analyticsId
        for (AlertRecord existing : activeStage.getAlerts()) {
            if (existing.isActive() && existing.getTriggerAnalyticsId() == triggerAnalyticsId) {
                log.debug("Alert already active for plate={} analyticsId={}, ignoring",
                        plate, triggerAnalyticsId);
                return; // Don't create duplicate
            }
        }

        AlertRecord alert = new AlertRecord();
        alert.setPlateNumber(plate);
        alert.setMessage(ac.getMessage());
        alert.setTimeoutSeconds(ac.getSendTimeOutMinutes() * 60);
        alert.setActive(true);
        alert.setTriggerAnalyticsId(triggerAnalyticsId);
        activeStage.getAlerts().add(alert);
        allAlerts.add(alert);

        log.debug("Created alert for plate={}: '{}', timeout={}min",
                plate, ac.getMessage(), ac.getSendTimeOutMinutes());
    }

    private void deactivateAlertsOnDifferentCamera(String plate, int currentAnalyticsId) {
        for (AlertRecord alert : allAlerts) {
            if (alert.isActive() && alert.getPlateNumber().equals(plate)
                    && alert.getTriggerAnalyticsId() != currentAnalyticsId) {
                alert.setActive(false);
                log.debug("Deactivated alert for plate={} (different camera detected)", plate);
            }
        }
    }

    // ========== MAINTENANCE ==========

    @Override
    public void performMaintenance(int elapsedSeconds) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        pendingAlertSends.clear();

        // 1. Decrement timeouts for transitional candidates
        decrementCandidateTimeouts(elapsedSeconds);

        // 2. Materialize candidates whose timeout <= 0
        materializeCandidates(now);

        // 3. Decrement alert timeouts
        decrementAlertTimeouts(elapsedSeconds);

        // 4. Recalculate durations for all active stages
        recalculateDurations(now);

        // 5. Close timed-out sequences
        closeTimedOutSequences(now);
    }

    private void decrementCandidateTimeouts(int elapsedSeconds) {
        for (PlateSequence seq : activeSequences.values()) {
            for (Stage s : seq.getStages()) {
                if (s.isCandidate() && s.isActive()) {
                    s.setTimeout(s.getTimeout() - elapsedSeconds);
                }
            }
        }
    }

    private void materializeCandidates(LocalDateTime now) {
        for (PlateSequence seq : activeSequences.values()) {
            List<Stage> toMaterialize = new ArrayList<>();
            for (Stage s : seq.getStages()) {
                if (s.isCandidate() && s.isActive() && s.getTimeout() <= 0) {
                    toMaterialize.add(s);
                }
            }
            for (Stage candidate : toMaterialize) {
                candidate.setCandidate(false);
                candidate.setFull(true);
                candidate.setDurationSeconds(Duration.between(candidate.getInTime(), now).getSeconds());

                // Close previous active real stage
                Stage activeStage = null;
                for (Stage s : seq.getStages()) {
                    if (s != candidate && s.isActive() && !s.isCandidate()) {
                        activeStage = s;
                    }
                }
                if (activeStage != null) {
                    activeStage.setActive(false);
                    if (activeStage.getOutTime() == null) {
                        activeStage.setOutTime(candidate.getInTime().minusSeconds(1));
                    }
                }

                log.info("Materialized transitional '{}' for plate={}", candidate.getName(),
                        seq.getPlateNumber());
            }
        }
    }

    private void decrementAlertTimeouts(int elapsedSeconds) {
        for (AlertRecord alert : allAlerts) {
            if (alert.isActive()) {
                alert.setTimeoutSeconds(alert.getTimeoutSeconds() - elapsedSeconds);
                if (alert.getTimeoutSeconds() <= 0) {
                    alert.setActive(false);
                    pendingAlertSends.add(alert);
                }
            }
        }
    }

    private void recalculateDurations(LocalDateTime now) {
        for (PlateSequence seq : activeSequences.values()) {
            for (Stage s : seq.getStages()) {
                if (s.isActive() && !s.isCandidate()) {
                    s.recalculateDuration(now);
                }
            }
        }
        // Also recalculate for closed sequences (their stages are already inactive but may need duration)
    }

    private void closeTimedOutSequences(LocalDateTime now) {
        int globalTimeoutMinutes = configLoader.getConfig().getWorkflow().getSequenceCloseTimeoutMinutes();
        List<String> platesToClose = new ArrayList<>();

        for (Map.Entry<String, PlateSequence> entry : activeSequences.entrySet()) {
            PlateSequence seq = entry.getValue();
            if (seq.getLastDetectionTime() == null) continue;

            Stage activeStage = seq.getActiveStage();
            if (activeStage != null && "transitional".equals(activeStage.getType())) {
                if (activeStage.getSequenceCloseTimeoutOverrideMinutes() > 0) {
                    // override > 0: measure from stage inTime with override timeout
                    long minutesSinceStart = Duration.between(activeStage.getInTime(), now).toMinutes();
                    if (minutesSinceStart >= activeStage.getSequenceCloseTimeoutOverrideMinutes()) {
                        platesToClose.add(entry.getKey());
                    }
                } else {
                    // override == 0: measure from stage inTime with global timeout
                    if (activeStage.getInTime() != null) {
                        long minutesSinceStart = Duration.between(activeStage.getInTime(), now).toMinutes();
                        if (minutesSinceStart >= globalTimeoutMinutes) {
                            platesToClose.add(entry.getKey());
                        }
                    }
                }
                continue;
            }

            // Non-transitional: global timeout measured from last detection
            long minutesSinceLastDetection = Duration.between(seq.getLastDetectionTime(), now).toMinutes();
            if (minutesSinceLastDetection >= globalTimeoutMinutes) {
                platesToClose.add(entry.getKey());
            }
        }

        for (String plate : platesToClose) {
            closeSequence(plate, now);
        }
    }

    private void closeSequence(String plate, LocalDateTime now) {
        PlateSequence seq = activeSequences.remove(plate);
        if (seq == null) return;

        seq.setActive(false);
        seq.setCloseTime(now);

        Stage activeStage = seq.getActiveStage();
        if (activeStage != null) {
            if ("singleCamera".equals(activeStage.getType())) {
                // Single camera: outTime = lastDetection, duration = outTime - inTime
                if (activeStage.getOutTime() != null && activeStage.getInTime() != null) {
                    activeStage.setDurationSeconds(
                            Duration.between(activeStage.getInTime(), activeStage.getOutTime()).getSeconds());
                }
                activeStage.setActive(false);
            } else if ("transitional".equals(activeStage.getType())) {
                // Remove the transitional stage if it triggered the close
                seq.getStages().remove(activeStage);
            } else {
                // Real stage: saved with outTime = null, duration = null
                activeStage.setOutTime(null);
                activeStage.setDurationSeconds(null);
                activeStage.setActive(false);
            }
        }

        // Delete any pending candidates
        seq.getStages().removeIf(s -> s.isCandidate() && s.isActive());

        // Deactivate all active alerts for this plate
        for (AlertRecord alert : allAlerts) {
            if (alert.isActive() && alert.getPlateNumber().equals(plate)) {
                alert.setActive(false);
            }
        }

        closedSequences.add(seq);
        newlyClosedSequences.add(seq);
        log.info("Closed sequence for plate={}, stages={}", plate, seq.getStages().size());
    }

    // ========== GAP-BASED CLOSE CHECK ==========

    /**
     * Called at the top of processOneDetection.
     * If the gap between the plate's last detection and the new detection exceeds the configured
     * close timeout, the current active sequence is closed before the new detection is processed.
     * This handles both historical sequences (multiple disjoint visits) and runtime gap detection.
     */
    private void checkAndCloseForDetectionGap(String plate, LocalDateTime detectionTime) {
        PlateSequence seq = activeSequences.get(plate);
        if (seq == null || seq.getLastDetectionTime() == null) return;

        WorkflowConfig wf = configLoader.getConfig().getWorkflow();
        int globalTimeoutMinutes = wf.getSequenceCloseTimeoutMinutes();

        // Mirrors closeTimedOutSequences logic for transitional stages
        Stage activeStage = seq.getActiveStage();
        if (activeStage != null && "transitional".equals(activeStage.getType())) {
            if (activeStage.getSequenceCloseTimeoutOverrideMinutes() > 0) {
                // override > 0: measure from stage inTime with override timeout
                long minutesSinceStart = Duration.between(activeStage.getInTime(), detectionTime).toMinutes();
                if (minutesSinceStart >= activeStage.getSequenceCloseTimeoutOverrideMinutes()) {
                    closeSequence(plate, detectionTime);
                }
            } else {
                // override == 0: measure from stage inTime with global timeout
                if (activeStage.getInTime() != null) {
                    long minutesSinceStart = Duration.between(activeStage.getInTime(), detectionTime).toMinutes();
                    if (minutesSinceStart >= globalTimeoutMinutes) {
                        closeSequence(plate, detectionTime);
                    }
                }
            }
            return;
        }

        // Non-transitional: measure from last detection
        long minutesSinceLastDetection = Duration.between(seq.getLastDetectionTime(), detectionTime).toMinutes();
        if (minutesSinceLastDetection >= globalTimeoutMinutes) {
            closeSequence(plate, detectionTime);
        }
    }

    // ========== HISTORICAL TRANSITIONAL CHECK ==========

    /**
     * After all historical detections are processed, check for transitional stages
     * that should have been inserted between stages based on timing gaps.
     */
    public void insertHistoricalTransitionals() {
        WorkflowConfig wf = configLoader.getConfig().getWorkflow();

        for (PlateSequence seq : getAllSequences()) {
            List<Stage> stages = seq.getStages();
            List<Stage> toInsert = new ArrayList<>();

            for (int i = 0; i < stages.size() - 1; i++) {
                Stage stageA = stages.get(i);
                Stage stageB = stages.get(i + 1);

                if (stageA.isCandidate() || stageB.isCandidate()) continue;
                if (stageA.getOutTime() == null || stageB.getInTime() == null) continue;

                for (TransitionalStageConfig tc : wf.getTransitional()) {
                    if (!tc.getAllowedAfter().contains(stageA.getName())) continue;

                    // Use seconds to avoid toMinutes() truncation swallowing valid sub-minute gaps
                    long gapSeconds = Duration.between(stageA.getOutTime(), stageB.getInTime()).toSeconds();
                    if (gapSeconds > (long) tc.getCandidateTimeoutMinutes() * 60) {
                        Stage transitional = new Stage();
                        transitional.setName(tc.getName());
                        transitional.setLabel(tc.getLabel());
                        transitional.setType("transitional");
                        transitional.setActive(false);
                        transitional.setFull(true);
                        transitional.setCandidate(false);
                        transitional.setInTime(stageA.getOutTime().plusSeconds(1));
                        transitional.setOutTime(stageB.getInTime().minusSeconds(1));
                        transitional.setPlateNumber(seq.getPlateNumber());
                        transitional.setTimeout(0);
                        transitional.setSequenceCloseTimeoutOverrideMinutes(
                                tc.getSequenceCloseTimeoutOverrideMinutes());
                        transitional.recalculateDuration(LocalDateTime.now(ZoneOffset.UTC));

                        toInsert.add(transitional);
                        // Mark insert position
                        transitional.setId(i + 1); // temporary marker for insertion index
                        log.debug("Inserting historical transitional '{}' between '{}' and '{}' for plate={}",
                                tc.getName(), stageA.getName(), stageB.getName(), seq.getPlateNumber());
                    }
                }
            }

            // Insert in reverse order to maintain correct indices
            toInsert.sort((a, b) -> Long.compare(b.getId(), a.getId()));
            for (Stage s : toInsert) {
                int insertIdx = (int) s.getId();
                s.setId(0); // clear temporary marker
                stages.add(insertIdx, s);
            }
        }
    }

    @Override
    public List<PlateSequence> getAllSequences() {
        List<PlateSequence> all = new ArrayList<>();
        all.addAll(closedSequences);
        all.addAll(activeSequences.values());
        return all;
    }

    /**
     * Returns only the currently active sequences (used for incremental polling writes).
     */
    public List<PlateSequence> getActiveSequences() {
        return new ArrayList<>(activeSequences.values());
    }

    /**
     * Get alerts that are ready to be sent (timeout expired during last maintenance).
     */
    public List<AlertRecord> getPendingAlertSends() {
        return new ArrayList<>(pendingAlertSends);
    }

    @Override
    public List<PlateSequence> getNewlyClosedSequences() {
        return new ArrayList<>(newlyClosedSequences);
    }

    @Override
    public void clearNewlyClosedSequences() {
        newlyClosedSequences.clear();
    }
}
