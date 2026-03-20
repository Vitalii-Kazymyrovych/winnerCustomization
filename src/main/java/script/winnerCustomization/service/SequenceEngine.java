package script.winnerCustomization.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.SequenceRecord;
import script.winnerCustomization.model.SequenceRecord.StageWindow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class SequenceEngine {
    private static final Logger log = LoggerFactory.getLogger(SequenceEngine.class);
    private static final Duration DEFAULT_SEQUENCE_GAP_TIMEOUT = Duration.ofHours(48);

    public List<SequenceRecord> build(List<Detection> detections, AppConfig config) {
        log.info("Sequence build started for {} detections", detections.size());
        WorkflowDefinition workflow = WorkflowDefinition.from(config);
        Map<String, ActiveSequence> activeByPlate = new HashMap<>();
        Map<String, LocalDateTime> lastNormalizedAtByPlate = new HashMap<>();
        List<SequenceRecord> done = new ArrayList<>();

        List<Detection> sorted = detections.stream()
                .sorted(Comparator.comparing(Detection::createdAt).thenComparingLong(Detection::id))
                .toList();

        for (Detection detection : sorted) {
            TriggerMatch match = workflow.resolve(detection);
            if (match == null) {
                continue;
            }

            LocalDateTime eventTime = normalizeTimestamp(detection.plateNumber(), detection.createdAt(), lastNormalizedAtByPlate);
            ActiveSequence current = activeByPlate.get(detection.plateNumber());

            if (current != null && workflow.shouldCloseBySequenceGap(current, eventTime)) {
                addIfNotEmpty(done, workflow.finalizeSequence(current));
                activeByPlate.remove(detection.plateNumber());
                current = null;
            }

            if (current != null) {
                SequenceLifecycle lifecycle = workflow.handlePendingTimeouts(current, eventTime);
                if (lifecycle == SequenceLifecycle.CLOSE_SEQUENCE) {
                    addIfNotEmpty(done, workflow.finalizeSequence(current));
                    activeByPlate.remove(detection.plateNumber());
                    current = null;
                }
            }

            if (current == null) {
                current = new ActiveSequence(detection.plateNumber(), eventTime);
                activeByPlate.put(detection.plateNumber(), current);
            }

            workflow.cancelPendingCandidateIfNeeded(current, match, eventTime);
            workflow.applyEvent(current, match, eventTime);
            current.lastEventAt = eventTime;
        }

        for (ActiveSequence active : activeByPlate.values()) {
            addIfNotEmpty(done, workflow.finalizeSequence(active));
        }

        done.sort(Comparator.comparing(SequenceRecord::getStartedAt)
                .thenComparing(SequenceRecord::getPlateNumber));
        return done;
    }

    private LocalDateTime normalizeTimestamp(String plateNumber,
                                             LocalDateTime createdAt,
                                             Map<String, LocalDateTime> lastNormalizedAtByPlate) {
        LocalDateTime last = lastNormalizedAtByPlate.get(plateNumber);
        LocalDateTime normalized = createdAt;
        if (last != null && !normalized.isAfter(last)) {
            normalized = last.plusSeconds(1);
        }
        lastNormalizedAtByPlate.put(plateNumber, normalized);
        return normalized;
    }

    private void addIfNotEmpty(List<SequenceRecord> done, SequenceRecord record) {
        if (!record.getStages().isEmpty()) {
            done.add(record);
        }
    }

    private enum SequenceLifecycle {
        CONTINUE,
        CLOSE_SEQUENCE
    }

    private enum TriggerKind {
        START,
        FINISH
    }

    private record TriggerMatch(StageDefinition stage,
                                TriggerDefinition trigger,
                                String reportLabel) {
        String eventKey() {
            return trigger.eventKey();
        }

        TriggerKind kind() {
            return trigger.kind();
        }
    }

    private static final class WorkflowDefinition {
        private final AppConfig config;
        private final int defaultSequenceCloseTimeoutMinutes;
        private final Map<String, StageDefinition> stagesByName;
        private final List<TriggerDefinition> orderedTriggers;

        private WorkflowDefinition(AppConfig config,
                                   int defaultSequenceCloseTimeoutMinutes,
                                   Map<String, StageDefinition> stagesByName,
                                   List<TriggerDefinition> orderedTriggers) {
            this.config = config;
            this.defaultSequenceCloseTimeoutMinutes = defaultSequenceCloseTimeoutMinutes;
            this.stagesByName = stagesByName;
            this.orderedTriggers = orderedTriggers;
        }

        static WorkflowDefinition from(AppConfig config) {
            AppConfig.WorkflowConfig workflow = config == null ? null : config.getWorkflow();
            int defaultTimeout = workflow == null
                    ? (int) DEFAULT_SEQUENCE_GAP_TIMEOUT.toMinutes()
                    : workflow.getDefaultSequenceCloseTimeoutMinutes();
            Map<String, StageDefinition> stages = new LinkedHashMap<>();
            List<TriggerDefinition> triggers = new ArrayList<>();
            if (workflow != null && workflow.getStages() != null) {
                for (AppConfig.StageConfig stageConfig : workflow.getStages()) {
                    StageDefinition definition = StageDefinition.from(stageConfig);
                    stages.put(definition.name(), definition);
                    triggers.addAll(definition.startTriggers());
                    triggers.addAll(definition.finishTriggers());
                }
            }
            return new WorkflowDefinition(config, defaultTimeout, stages, triggers);
        }

        TriggerMatch resolve(Detection detection) {
            for (TriggerDefinition trigger : orderedTriggers) {
                if (trigger.matches(detection)) {
                    StageDefinition stage = stagesByName.get(trigger.stageName());
                    return new TriggerMatch(stage, trigger, stage.renderLabel(trigger.derivedInstance()));
                }
            }
            return null;
        }

        boolean shouldCloseBySequenceGap(ActiveSequence current, LocalDateTime eventTime) {
            if (current.lastEventAt == null) {
                return false;
            }
            Duration gap = Duration.between(current.lastEventAt, eventTime);
            return gap.compareTo(Duration.ofMinutes(resolveSequenceCloseTimeoutMinutes(current))) > 0;
        }

        SequenceLifecycle handlePendingTimeouts(ActiveSequence current, LocalDateTime eventTime) {
            if (current.pendingCandidate != null) {
                PendingCandidate candidate = current.pendingCandidate;
                Duration candidateAge = Duration.between(candidate.triggerAt(), eventTime);
                if (candidate.closeTimeoutMinutes() != null
                        && candidateAge.toMinutes() >= candidate.closeTimeoutMinutes()) {
                    current.pendingCandidate = null;
                    current.record.setFinishedAt(current.lastEventAt);
                    return SequenceLifecycle.CLOSE_SEQUENCE;
                }
                if (candidateAge.toMinutes() >= candidate.timeoutMinutes()) {
                    if (current.activeStageIndex != null) {
                        LocalDateTime candidateStart = candidate.triggerAt().minusSeconds(1);
                        if (candidateStart.isBefore(current.activeStage().timeIn() == null ? candidate.triggerAt() : current.activeStage().timeIn())) {
                            candidateStart = candidate.triggerAt();
                        }
                        closeActiveAt(current, candidateStart);
                    }
                    createClosedStage(current,
                            candidate.stage(),
                            candidate.reportLabel(),
                            candidate.triggerAt(),
                            eventTime.minusSeconds(1),
                            false,
                            candidate.stage().transitional());
                    current.pendingCandidate = null;
                }
            }

            if (current.activeStageIndex != null && current.activeStage().sticky() && current.pendingStickyOutAt != null) {
                StageDefinition activeStage = current.activeStageDefinition(this);
                if (activeStage.stickyCloseTimeoutMinutes() != null) {
                    Duration stickyAge = Duration.between(current.pendingStickyOutAt, eventTime);
                    if (stickyAge.toMinutes() >= activeStage.stickyCloseTimeoutMinutes()) {
                        LocalDateTime stickyCloseAt = current.pendingStickyOutAt;
                        closeActiveAt(current, stickyCloseAt);
                        current.pendingStickyOutAt = null;
                        materializeTimeoutTransitionIfNeeded(current, activeStage, stickyCloseAt.plusSeconds(1), eventTime.minusSeconds(1));
                    }
                }
            }
            return SequenceLifecycle.CONTINUE;
        }

        void cancelPendingCandidateIfNeeded(ActiveSequence current, TriggerMatch match, LocalDateTime eventTime) {
            if (current.pendingCandidate == null) {
                return;
            }
            PendingCandidate candidate = current.pendingCandidate;
            if (Duration.between(candidate.triggerAt(), eventTime).toMinutes() >= candidate.timeoutMinutes()) {
                return;
            }
            if (Objects.equals(candidate.stage().name(), match.stage().name()) && match.kind() == TriggerKind.START) {
                if ("refresh_candidate".equals(candidate.stage().startDuplicatePolicy())) {
                    current.pendingCandidate = candidate.withTriggerAt(eventTime);
                }
                return;
            }
            if (candidate.cancelOnEvents().isEmpty() || candidate.cancelOnEvents().contains(match.eventKey())) {
                current.pendingCandidate = null;
            }
        }

        void applyEvent(ActiveSequence current, TriggerMatch match, LocalDateTime eventTime) {
            current.record.addPathStep(match.eventKey());
            if (match.kind() == TriggerKind.START) {
                if ("candidate".equals(match.stage().startMode())) {
                    applyCandidateStart(current, match, eventTime);
                } else {
                    applyImmediateStart(current, match, eventTime);
                }
                return;
            }
            applyFinish(current, match, eventTime);
        }

        private void applyCandidateStart(ActiveSequence current, TriggerMatch match, LocalDateTime eventTime) {
            if (current.pendingCandidate != null && Objects.equals(current.pendingCandidate.stage().name(), match.stage().name())) {
                if ("refresh_candidate".equals(match.stage().startDuplicatePolicy())) {
                    current.pendingCandidate = current.pendingCandidate.withTriggerAt(eventTime);
                }
                return;
            }
            current.pendingCandidate = new PendingCandidate(
                    match.stage(),
                    match.reportLabel(),
                    eventTime,
                    positiveOrNull(match.stage().candidateTimeoutMinutes(), 0),
                    match.stage().candidateCloseTimeoutMinutes(),
                    new HashSet<>(match.stage().candidateCancelOnEvents())
            );
        }

        private void applyImmediateStart(ActiveSequence current, TriggerMatch match, LocalDateTime eventTime) {
            StageDefinition nextStage = match.stage();
            if (current.activeStageIndex != null) {
                StageDefinition activeStage = current.activeStageDefinition(this);
                StageWindow active = current.activeStage();

                if (Objects.equals(active.stageName(), nextStage.name())) {
                    if (canReopenSameStage(active, nextStage, eventTime) || "restart".equals(nextStage.startDuplicatePolicy())) {
                        closeActiveAt(current, eventTime.minusSeconds(1));
                    } else {
                        return;
                    }
                } else if (!isAllowedNext(activeStage, nextStage.name())) {
                    String policy = normalizePolicy(activeStage.unexpectedNextStagePolicy());
                    if ("ignore".equals(policy)) {
                        return;
                    }
                    if ("start_partial_next".equals(policy)) {
                        addPartialStage(current, nextStage, match.reportLabel(), eventTime);
                        return;
                    }
                    closeCurrentForTransition(current, activeStage, nextStage, eventTime);
                    if ("insert_intermediate_and_start_next".equals(policy)) {
                        insertIntermediateStage(current, activeStage.intermediateStageOnTransition(), eventTime);
                    }
                } else {
                    closeCurrentForTransition(current, activeStage, nextStage, eventTime);
                }
            }
            openStage(current, nextStage, match.reportLabel(), eventTime, false);
        }

        private void applyFinish(ActiveSequence current, TriggerMatch match, LocalDateTime eventTime) {
            StageDefinition stage = match.stage();
            if (current.activeStageIndex != null && Objects.equals(current.activeStage().stageName(), stage.name())) {
                if (current.activeStage().sticky()) {
                    if (current.pendingStickyOutAt == null || "update_sticky".equals(stage.finishDuplicatePolicy())) {
                        current.pendingStickyOutAt = eventTime;
                    }
                    return;
                }
                closeActiveAt(current, eventTime);
                openIntermediateOnFinish(current, stage, eventTime);
                return;
            }

            if (stage.allowPartialFromFinish()) {
                addPartialStage(current, stage, match.reportLabel(), eventTime);
                if (current.activeStageIndex == null || !current.activeStage().transitional()) {
                    openIntermediateOnFinish(current, stage, eventTime);
                }
            }
        }

        private void openIntermediateOnFinish(ActiveSequence current, StageDefinition stage, LocalDateTime eventTime) {
            if (stage.intermediateStageOnTransition() == null) {
                return;
            }
            StageDefinition intermediate = stagesByName.get(stage.intermediateStageOnTransition());
            if (intermediate == null) {
                return;
            }
            if (current.activeStageIndex != null && Objects.equals(current.activeStage().stageName(), intermediate.name())) {
                return;
            }
            openStage(current, intermediate, intermediate.renderLabel(null), eventTime, false);
        }

        private void closeCurrentForTransition(ActiveSequence current,
                                               StageDefinition activeStage,
                                               StageDefinition nextStage,
                                               LocalDateTime eventTime) {
            if (current.activeStage().sticky()) {
                LocalDateTime stickyCloseAt = current.pendingStickyOutAt != null
                        ? current.pendingStickyOutAt
                        : eventTime.minusSeconds(1);
                closeActiveAt(current, stickyCloseAt);
                current.pendingStickyOutAt = null;
                if (activeStage.timeoutTransitionToStage() != null
                        && !Objects.equals(activeStage.timeoutTransitionToStage(), nextStage.name())) {
                    materializeTimeoutTransitionIfNeeded(current, activeStage, stickyCloseAt.plusSeconds(1), eventTime.minusSeconds(1));
                }
                return;
            }
            closeActiveAt(current, eventTime.minusSeconds(1));
        }

        private void materializeTimeoutTransitionIfNeeded(ActiveSequence current,
                                                          StageDefinition activeStage,
                                                          LocalDateTime timeIn,
                                                          LocalDateTime timeOut) {
            if (activeStage.timeoutTransitionToStage() == null || timeIn == null || timeOut == null || timeIn.isAfter(timeOut)) {
                return;
            }
            StageDefinition timeoutStage = stagesByName.get(activeStage.timeoutTransitionToStage());
            if (timeoutStage == null) {
                return;
            }
            createClosedStage(current, timeoutStage, timeoutStage.renderLabel(null), timeIn, timeOut, false, true);
        }

        private void insertIntermediateStage(ActiveSequence current, String stageName, LocalDateTime eventTime) {
            if (stageName == null) {
                return;
            }
            StageDefinition stage = stagesByName.get(stageName);
            if (stage == null) {
                return;
            }
            createClosedStage(current,
                    stage,
                    stage.renderLabel(null),
                    eventTime,
                    eventTime,
                    false,
                    true);
        }

        private void addPartialStage(ActiveSequence current, StageDefinition stage, String reportLabel, LocalDateTime eventTime) {
            current.record.addStage(new StageWindow(
                    stage.name(),
                    reportLabel,
                    null,
                    eventTime,
                    "",
                    true,
                    "sticky".equals(stage.finishMode()),
                    stage.transitional(),
                    stage.saveAfterSequenceClosed(),
                    current.nextEventOrder()
            ));
        }

        private void openStage(ActiveSequence current,
                               StageDefinition stage,
                               String reportLabel,
                               LocalDateTime timeIn,
                               boolean partial) {
            current.record.addStage(new StageWindow(
                    stage.name(),
                    reportLabel,
                    timeIn,
                    null,
                    "",
                    partial,
                    "sticky".equals(stage.finishMode()),
                    stage.transitional(),
                    stage.saveAfterSequenceClosed(),
                    current.nextEventOrder()
            ));
            current.activeStageIndex = current.record.getStages().size() - 1;
            current.pendingStickyOutAt = null;
        }

        private void createClosedStage(ActiveSequence current,
                                       StageDefinition stage,
                                       String reportLabel,
                                       LocalDateTime timeIn,
                                       LocalDateTime timeOut,
                                       boolean partial,
                                       boolean transitionalOverride) {
            if (timeIn != null && timeOut != null && timeIn.isAfter(timeOut)) {
                return;
            }
            current.record.addStage(new StageWindow(
                    stage.name(),
                    reportLabel,
                    timeIn,
                    timeOut,
                    "",
                    partial,
                    "sticky".equals(stage.finishMode()),
                    transitionalOverride || stage.transitional(),
                    stage.saveAfterSequenceClosed(),
                    current.nextEventOrder()
            ));
        }

        private void closeActiveAt(ActiveSequence current, LocalDateTime timeOut) {
            if (current.activeStageIndex == null) {
                return;
            }
            StageWindow active = current.activeStage();
            if (active.timeOut() == null) {
                current.record.getStages().set(current.activeStageIndex, active.withTimeOut(timeOut));
            }
            current.activeStageIndex = null;
        }

        SequenceRecord finalizeSequence(ActiveSequence current) {
            if (current.activeStageIndex != null) {
                StageWindow active = current.activeStage();
                StageDefinition stage = current.activeStageDefinition(this);
                if (active.sticky() && current.pendingStickyOutAt != null) {
                    closeActiveAt(current, current.pendingStickyOutAt);
                    current.pendingStickyOutAt = null;
                } else if (!active.saveAfterSequenceClosed()) {
                    current.record.getStages().remove((int) current.activeStageIndex);
                    current.activeStageIndex = null;
                }
            }
            if (current.pendingCandidate != null && current.pendingCandidate.stage().saveAfterSequenceClosed()) {
                createClosedStage(current,
                        current.pendingCandidate.stage(),
                        current.pendingCandidate.reportLabel(),
                        current.pendingCandidate.triggerAt(),
                        current.lastEventAt,
                        false,
                        current.pendingCandidate.stage().transitional());
                current.pendingCandidate = null;
            }
            current.record.setFinishedAt(current.lastEventAt);
            annotateAlerts(current.record);
            return current.record;
        }

        private void annotateAlerts(SequenceRecord record) {
            List<StageWindow> updated = new ArrayList<>();
            for (StageWindow stage : record.getStages()) {
                String alert = resolveAlert(record, stage);
                updated.add(stage.withAlert(alert));
            }
            record.getStages().clear();
            record.getStages().addAll(updated);
        }

        private String resolveAlert(SequenceRecord record, StageWindow stage) {
            if (stage.partial() || stage.timeIn() == null) {
                return "";
            }
            StageDefinition definition = stagesByName.get(stage.stageName());
            if (definition == null) {
                return "";
            }
            TriggerDefinition trigger = definition.startTriggers().stream()
                    .filter(candidate -> candidate.notificationEnabled())
                    .findFirst()
                    .orElse(null);
            if (trigger == null || trigger.notificationDelayMinutes() == null) {
                return "";
            }
            LocalDateTime border = stage.timeOut() != null ? stage.timeOut() : record.getFinishedAt();
            if (border == null) {
                return "";
            }
            if (Duration.between(stage.timeIn(), border).toMinutes() < trigger.notificationDelayMinutes()) {
                return "";
            }
            return trigger.renderNotification(trigger.notificationDelayMinutes(), stage.reportLabel());
        }

        private int resolveSequenceCloseTimeoutMinutes(ActiveSequence current) {
            if (current.activeStageIndex != null) {
                StageDefinition active = current.activeStageDefinition(this);
                if (active != null && active.sequenceCloseTimeoutMinutes() != null) {
                    return active.sequenceCloseTimeoutMinutes();
                }
            }
            return defaultSequenceCloseTimeoutMinutes > 0
                    ? defaultSequenceCloseTimeoutMinutes
                    : (int) DEFAULT_SEQUENCE_GAP_TIMEOUT.toMinutes();
        }

        private boolean isAllowedNext(StageDefinition activeStage, String nextStageName) {
            return activeStage.allowedNextStages().isEmpty() || activeStage.allowedNextStages().contains(nextStageName);
        }

        private boolean canReopenSameStage(StageWindow active, StageDefinition definition, LocalDateTime eventTime) {
            if (definition.sameStageReopenAfterMinutes() == null || active.timeIn() == null) {
                return false;
            }
            return Duration.between(active.timeIn(), eventTime).toMinutes() >= definition.sameStageReopenAfterMinutes();
        }

        private Integer positiveOrNull(Integer value, int fallback) {
            if (value == null) {
                return fallback > 0 ? fallback : null;
            }
            return value > 0 ? value : null;
        }

        private String normalizePolicy(String value) {
            return value == null ? "close_current_and_start_next" : value.toLowerCase(Locale.ROOT);
        }
    }

    private record StageDefinition(String name,
                                   String labelTemplate,
                                   String startMode,
                                   Integer candidateTimeoutMinutes,
                                   Integer candidateCloseTimeoutMinutes,
                                   List<String> candidateCancelOnEvents,
                                   String finishMode,
                                   Integer stickyCloseTimeoutMinutes,
                                   List<String> allowedNextStages,
                                   String unexpectedNextStagePolicy,
                                   String timeoutTransitionToStage,
                                   Integer sequenceCloseTimeoutMinutes,
                                   boolean saveAfterSequenceClosed,
                                   boolean allowPartialFromFinish,
                                   String startDuplicatePolicy,
                                   String finishDuplicatePolicy,
                                   String intermediateStageOnTransition,
                                   boolean transitional,
                                   Integer sameStageReopenAfterMinutes,
                                   List<TriggerDefinition> startTriggers,
                                   List<TriggerDefinition> finishTriggers) {
        static StageDefinition from(AppConfig.StageConfig config) {
            List<TriggerDefinition> starts = toTriggerDefinitions(config, TriggerKind.START, config.getStartTriggers());
            List<TriggerDefinition> finishes = toTriggerDefinitions(config, TriggerKind.FINISH, config.getFinishTriggers());
            return new StageDefinition(
                    config.getName(),
                    config.getLabelTemplate(),
                    valueOrDefault(config.getStartMode(), "immediate"),
                    config.getCandidateTimeoutMinutes(),
                    config.getCandidateCloseTimeoutMinutes(),
                    config.getCandidateCancelOnEvents() == null ? List.of() : List.copyOf(config.getCandidateCancelOnEvents()),
                    valueOrDefault(config.getFinishMode(), "immediate"),
                    config.getStickyCloseTimeoutMinutes(),
                    config.getAllowedNextStages() == null ? List.of() : List.copyOf(config.getAllowedNextStages()),
                    valueOrDefault(config.getUnexpectedNextStagePolicy(), "close_current_and_start_next"),
                    config.getTimeoutTransitionToStage(),
                    config.getSequenceCloseTimeoutMinutes(),
                    config.getSaveStageAfterSequenceClosed() == null || config.getSaveStageAfterSequenceClosed(),
                    config.isAllowPartialFromFinish(),
                    valueOrDefault(config.getStartDuplicatePolicy(), "ignore"),
                    valueOrDefault(config.getFinishDuplicatePolicy(), "update_sticky"),
                    config.getIntermediateStageOnTransition(),
                    config.isTransitional(),
                    config.getSameStageReopenAfterMinutes(),
                    starts,
                    finishes
            );
        }

        String renderLabel(String derivedInstance) {
            if (labelTemplate == null || labelTemplate.isBlank()) {
                return name;
            }
            String resolved = labelTemplate;
            if (derivedInstance != null && !derivedInstance.isBlank()) {
                resolved = resolved.replace("{{instance}}", derivedInstance);
            }
            return resolved;
        }

        private static List<TriggerDefinition> toTriggerDefinitions(AppConfig.StageConfig stageConfig,
                                                                    TriggerKind kind,
                                                                    List<AppConfig.TriggerConfig> configs) {
            if (configs == null) {
                return List.of();
            }
            List<TriggerDefinition> definitions = new ArrayList<>();
            for (AppConfig.TriggerConfig trigger : configs) {
                definitions.add(new TriggerDefinition(
                        stageConfig.getName(),
                        kind,
                        trigger.getCameraId(),
                        trigger.getDirectionRange(),
                        trigger.getEventKey(),
                        trigger.getDerivedStageInstance(),
                        trigger.getName(),
                        trigger.getNotification() != null && trigger.getNotification().isEnabled(),
                        trigger.getNotification() == null ? null : trigger.getNotification().getTemplate(),
                        trigger.getNotification() == null ? null : trigger.getNotification().getDelayMinutes()
                ));
            }
            return definitions;
        }

        private static String valueOrDefault(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }

    private record TriggerDefinition(String stageName,
                                     TriggerKind kind,
                                     Integer cameraId,
                                     AppConfig.DirectionRange directionRange,
                                     String eventKey,
                                     String derivedInstance,
                                     String name,
                                     boolean notificationEnabled,
                                     String notificationTemplate,
                                     Integer notificationDelayMinutes) {
        boolean matches(Detection detection) {
            return Objects.equals(cameraId, detection.analyticsId())
                    && (directionRange == null || directionRange.contains(detection.direction()));
        }

        String renderNotification(Integer threshold, String stageLabel) {
            String template = notificationTemplate == null ? "" : notificationTemplate;
            return template
                    .replace("{{threshold}}", String.valueOf(threshold))
                    .replace("{{stage}}", stageLabel == null ? "" : stageLabel);
        }
    }

    private record PendingCandidate(StageDefinition stage,
                                    String reportLabel,
                                    LocalDateTime triggerAt,
                                    Integer timeoutMinutes,
                                    Integer closeTimeoutMinutes,
                                    Set<String> cancelOnEvents) {
        PendingCandidate withTriggerAt(LocalDateTime newTriggerAt) {
            return new PendingCandidate(stage, reportLabel, newTriggerAt, timeoutMinutes, closeTimeoutMinutes, cancelOnEvents);
        }
    }

    private static final class ActiveSequence {
        private final SequenceRecord record;
        private LocalDateTime lastEventAt;
        private Integer activeStageIndex;
        private int eventOrder;
        private PendingCandidate pendingCandidate;
        private LocalDateTime pendingStickyOutAt;

        private ActiveSequence(String plateNumber, LocalDateTime startedAt) {
            this.record = new SequenceRecord(plateNumber, startedAt);
            this.lastEventAt = startedAt;
        }

        private int nextEventOrder() {
            return ++eventOrder;
        }

        private StageWindow activeStage() {
            return record.getStages().get(activeStageIndex);
        }

        private StageDefinition activeStageDefinition(WorkflowDefinition workflowDefinition) {
            if (activeStageIndex == null) {
                return null;
            }
            return workflowDefinition.stagesByName.get(activeStage().stageName());
        }
    }
}
