package script.winnerCustomization.service.logic;

import org.springframework.stereotype.Service;
import script.winnerCustomization.alerts.AlertSender;
import script.winnerCustomization.config.AlertRuleConfig;
import script.winnerCustomization.config.AppConfig;
import script.winnerCustomization.config.StageRuleConfig;
import script.winnerCustomization.config.TriggerConfig;
import script.winnerCustomization.model.Alert;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.Sequence;
import script.winnerCustomization.model.Stage;
import script.winnerCustomization.model.StageType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SequenceEngineServiceImpl implements SequenceEngineService {
    private final AppConfig appConfig;
    private final AlertSender alertSender;

    public SequenceEngineServiceImpl(AppConfig appConfig, AlertSender alertSender) {
        this.appConfig = appConfig;
        this.alertSender = alertSender;
    }

    @Override
    public EngineSnapshot rebuild(List<Detection> detections, LocalDateTime nowUtc) {
        List<Detection> ordered = detections.stream()
            .sorted(Comparator.comparing(Detection::createdAtUtc).thenComparing(Detection::id))
            .toList();

        Map<String, List<Sequence>> byPlate = new HashMap<>();
        List<Alert> alerts = new ArrayList<>();
        LocalDateTime lastProcessed = null;

        for (Detection detection : ordered) {
            lastProcessed = detection.createdAtUtc();
            Sequence sequence = getOrCreateOpenSequence(byPlate, detection.plate(), detection.createdAtUtc());
            sequence.setLastDetection(detection.createdAtUtc());
            applyDetection(sequence, detection);
            processAlerts(sequence, detection, alerts);
        }

        List<Sequence> allSequences = flatten(byPlate);
        backfillHistoricalTransitionals(allSequences);
        closeExpiredSequences(allSequences, nowUtc);
        recalculateDurations(allSequences, nowUtc);
        tickTransitionalCandidates(allSequences, secondsBetween(lastProcessed, nowUtc));
        tickAlerts(alerts, nowUtc);

        return new EngineSnapshot(allSequences, alerts, lastProcessed);
    }

    @Override
    public EngineSnapshot applyIncremental(List<Sequence> currentSequences,
                                           List<Alert> currentAlerts,
                                           List<Detection> newDetections,
                                           LocalDateTime previousTickUtc,
                                           LocalDateTime nowUtc) {
        Map<String, List<Sequence>> byPlate = new HashMap<>();
        for (Sequence sequence : currentSequences) {
            byPlate.computeIfAbsent(sequence.getPlate(), plate -> new ArrayList<>()).add(sequence);
        }

        List<Alert> alerts = new ArrayList<>(currentAlerts);
        LocalDateTime lastProcessed = newDetections.stream()
            .max(Comparator.comparing(Detection::createdAtUtc).thenComparing(Detection::id))
            .map(Detection::createdAtUtc)
            .orElse(null);

        List<Detection> ordered = newDetections.stream()
            .sorted(Comparator.comparing(Detection::createdAtUtc).thenComparing(Detection::id))
            .toList();
        for (Detection detection : ordered) {
            Sequence sequence = getOrCreateOpenSequence(byPlate, detection.plate(), detection.createdAtUtc());
            sequence.setLastDetection(detection.createdAtUtc());
            applyDetection(sequence, detection);
            processAlerts(sequence, detection, alerts);
        }

        List<Sequence> allSequences = flatten(byPlate);
        backfillHistoricalTransitionals(allSequences);
        int elapsedSeconds = secondsBetween(previousTickUtc, nowUtc);
        tickTransitionalCandidates(allSequences, elapsedSeconds);
        closeExpiredSequences(allSequences, nowUtc);
        recalculateDurations(allSequences, nowUtc);
        tickAlerts(alerts, nowUtc);

        return new EngineSnapshot(allSequences, alerts, lastProcessed);
    }

    private Sequence newSequence(String plate, LocalDateTime timestamp) {
        Sequence sequence = new Sequence();
        sequence.setPlate(plate);
        sequence.setLastDetection(timestamp);
        return sequence;
    }

    private void applyDetection(Sequence sequence, Detection detection) {
        removePendingCandidates(sequence);

        Stage active = findActiveStage(sequence);
        StageMatch match = findMatch(detection);
        if (match == null) return;

        if (!match.in && match.type == StageType.REAL) {
            createTransitionalCandidates(sequence, detection.createdAtUtc(), match.rule.getName());
        }

        if (match.in) {
            if (active != null && active.getName().equals(match.rule.getName())) {
                if (active.getType() == StageType.SINGLE_CAMERA) {
                    active.setOutTime(detection.createdAtUtc());
                    active.setLastDetectionTime(detection.createdAtUtc());
                }
                return;
            }
            if (active != null) {
                closeCurrentStageForNewIn(active);
            }
            Stage newStage = newStage(match.rule, match.type, detection.plate());
            newStage.setInTime(detection.createdAtUtc());
            newStage.setLastDetectionTime(detection.createdAtUtc());
            newStage.setActive(true);
            newStage.setFull(true);
            if (match.type == StageType.SINGLE_CAMERA) {
                newStage.setOutTime(detection.createdAtUtc());
            }
            sequence.getStages().add(newStage);
            return;
        }

        if (active == null) {
            Stage partial = newStage(match.rule, match.type, detection.plate());
            partial.setOutTime(detection.createdAtUtc());
            partial.setLastDetectionTime(detection.createdAtUtc());
            partial.setFull(false);
            partial.setActive(true);
            sequence.getStages().add(partial);
            return;
        }

        if (active.getName().equals(match.rule.getName())) {
            active.setOutTime(detection.createdAtUtc());
            active.setLastDetectionTime(detection.createdAtUtc());
            if (active.getInTime() == null) {
                active.setInTime(active.getOutTime());
                active.setFull(true);
            }
            return;
        }

        if (active.getOutTime() == null && active.getInTime() != null) {
            active.setOutTime(detection.createdAtUtc().minusSeconds(1));
            active.setFull(true);
        }
        active.setActive(false);

        Stage partial = newStage(match.rule, match.type, detection.plate());
        partial.setOutTime(detection.createdAtUtc());
        partial.setLastDetectionTime(detection.createdAtUtc());
        partial.setFull(false);
        partial.setActive(true);
        sequence.getStages().add(partial);
    }

    private void removePendingCandidates(Sequence sequence) {
        sequence.getStages().removeIf(stage ->
            stage.isActive() && stage.getType() == StageType.TRANSITIONAL && stage.getTimeoutSeconds() > 0);
    }

    private void closeCurrentStageForNewIn(Stage active) {
        if (active.getType() == StageType.TRANSITIONAL && active.getInTime() != null && active.getOutTime() == null) {
            active.setOutTime(active.getLastDetectionTime());
        }
        if (active.getType() == StageType.SINGLE_CAMERA && active.getOutTime() == null) {
            active.setOutTime(active.getLastDetectionTime());
        }
        active.setActive(false);
    }

    private Stage newStage(StageRuleConfig rule, StageType stageType, String plate) {
        Stage stage = new Stage();
        stage.setName(rule.getName());
        stage.setLabel(rule.getLabel());
        stage.setType(stageType);
        stage.setPlate(plate);
        stage.setTimeoutSeconds(stageType == StageType.TRANSITIONAL ? rule.getCandidateTimeoutMinutes() * 60 : 0);
        stage.setSequenceCloseTimeoutOverrideMinutes(rule.getSequenceCloseTimeoutOverrideMinutes());
        return stage;
    }

    private void createTransitionalCandidates(Sequence sequence, LocalDateTime detectionTime, String realStageName) {
        for (StageRuleConfig transitional : appConfig.getWorkflow().getTransitional()) {
            if (!transitional.getAllowedAfter().contains(realStageName)) continue;
            boolean exists = sequence.getStages().stream()
                .anyMatch(s -> s.isActive() && s.getType() == StageType.TRANSITIONAL && s.getName().equals(transitional.getName()));
            if (exists) continue;
            Stage candidate = newStage(transitional, StageType.TRANSITIONAL, sequence.getPlate());
            candidate.setActive(true);
            candidate.setFull(false);
            candidate.setInTime(detectionTime.plusSeconds(1));
            candidate.setLastDetectionTime(detectionTime);
            sequence.getStages().add(candidate);
        }
    }

    private void backfillHistoricalTransitionals(List<Sequence> sequences) {
        for (Sequence sequence : sequences) {
            List<Stage> stages = sequence.getStages();
            for (int i = 0; i < stages.size() - 1; i++) {
                Stage stageA = stages.get(i);
                if (stageA.getOutTime() == null) continue;

                Stage nextWithIn = null;
                for (int j = i + 1; j < stages.size(); j++) {
                    Stage candidate = stages.get(j);
                    if (candidate.getInTime() != null) {
                        nextWithIn = candidate;
                        break;
                    }
                }
                if (nextWithIn == null) continue;

                for (StageRuleConfig transitional : appConfig.getWorkflow().getTransitional()) {
                    if (!transitional.getAllowedAfter().contains(stageA.getName())) continue;
                    long gapSeconds = Duration.between(stageA.getOutTime(), nextWithIn.getInTime()).getSeconds();
                    if (gapSeconds <= transitional.getCandidateTimeoutMinutes() * 60L) continue;

                    LocalDateTime inTime = stageA.getOutTime().plusSeconds(1);
                    LocalDateTime outTime = nextWithIn.getInTime().minusSeconds(1);
                    if (outTime.isBefore(inTime)) continue;
                    boolean exists = stages.stream().anyMatch(s ->
                        s.getType() == StageType.TRANSITIONAL
                            && s.getName().equals(transitional.getName())
                            && s.getInTime() != null
                            && s.getOutTime() != null
                            && !s.getInTime().isBefore(inTime)
                            && !s.getOutTime().isAfter(outTime));
                    if (exists) continue;

                    Stage historical = newStage(transitional, StageType.TRANSITIONAL, sequence.getPlate());
                    historical.setInTime(inTime);
                    historical.setOutTime(outTime);
                    historical.setLastDetectionTime(outTime);
                    historical.setFull(true);
                    historical.setTimeoutSeconds(0);
                    historical.setActive(false);
                    stages.add(i + 1, historical);
                    i++;
                    break;
                }
            }
        }
    }

    private StageMatch findMatch(Detection detection) {
        for (StageRuleConfig single : appConfig.getWorkflow().getSingleCamera()) {
            if (single.getAnalyticsId() != null && single.getAnalyticsId() == detection.analyticsId()) {
                return new StageMatch(single, StageType.SINGLE_CAMERA, true);
            }
        }
        for (StageRuleConfig real : appConfig.getWorkflow().getReal()) {
            Optional<TriggerConfig> out = real.getTriggers().stream().filter(t -> "out".equalsIgnoreCase(t.getType()) && triggerMatches(t, detection)).findFirst();
            if (out.isPresent()) return new StageMatch(real, StageType.REAL, false);
            Optional<TriggerConfig> in = real.getTriggers().stream().filter(t -> "in".equalsIgnoreCase(t.getType()) && triggerMatches(t, detection)).findFirst();
            if (in.isPresent()) return new StageMatch(real, StageType.REAL, true);
        }
        for (StageRuleConfig tr : appConfig.getWorkflow().getTransitional()) {
            Optional<TriggerConfig> out = tr.getTriggers().stream().filter(t -> "out".equalsIgnoreCase(t.getType()) && triggerMatches(t, detection)).findFirst();
            if (out.isPresent()) return new StageMatch(tr, StageType.TRANSITIONAL, false);
            Optional<TriggerConfig> in = tr.getTriggers().stream().filter(t -> "in".equalsIgnoreCase(t.getType()) && triggerMatches(t, detection)).findFirst();
            if (in.isPresent()) return new StageMatch(tr, StageType.TRANSITIONAL, true);
        }
        return null;
    }

    private boolean triggerMatches(TriggerConfig trigger, Detection detection) {
        if (trigger.getAnalyticsId() != detection.analyticsId()) return false;
        if (trigger.getDirection() == null) return true;
        if (detection.direction() == null || detection.direction() < 0) return false;
        return angularDistance(trigger.getDirection(), detection.direction()) <= 90;
    }

    private int angularDistance(int a, int b) {
        int diff = Math.abs(a - b) % 360;
        return Math.min(diff, 360 - diff);
    }

    private Stage findActiveStage(Sequence sequence) {
        for (int i = sequence.getStages().size() - 1; i >= 0; i--) {
            Stage stage = sequence.getStages().get(i);
            if (stage.isActive()) return stage;
        }
        return null;
    }

    private void closeExpiredSequences(Iterable<Sequence> sequences, LocalDateTime nowUtc) {
        for (Sequence sequence : sequences) {
            if (sequence.isClosed()) continue;
            if (sequence.getLastDetection() == null) continue;
            Stage active = findActiveStage(sequence);
            int closeTimeoutMinutes = appConfig.getWorkflow().getSequenceCloseTimeoutMinutes();
            if (active != null && active.getType() == StageType.TRANSITIONAL && active.getSequenceCloseTimeoutOverrideMinutes() > 0) {
                closeTimeoutMinutes = active.getSequenceCloseTimeoutOverrideMinutes();
            }
            long minutes = Duration.between(sequence.getLastDetection(), nowUtc).toMinutes();
            if (minutes >= closeTimeoutMinutes) {
                if (active != null) {
                    if (active.getType() == StageType.SINGLE_CAMERA && active.getOutTime() == null) {
                        active.setOutTime(sequence.getLastDetection());
                    }
                    if (active.getType() != StageType.SINGLE_CAMERA) {
                        active.setOutTime(null);
                        active.setDuration(null);
                    }
                    active.setActive(false);
                }
                sequence.setClosed(true);
                sequence.setClosedAtUtc(nowUtc);
            }
        }
    }

    private void recalculateDurations(Iterable<Sequence> sequences, LocalDateTime nowUtc) {
        for (Sequence sequence : sequences) {
            for (Stage stage : sequence.getStages()) {
                if (stage.getInTime() == null) {
                    stage.setDuration(null);
                    continue;
                }
                if (stage.isActive() && stage.getOutTime() == null) {
                    stage.setDuration(Duration.between(stage.getInTime(), nowUtc));
                } else if (stage.getOutTime() != null) {
                    stage.setDuration(Duration.between(stage.getInTime(), stage.getOutTime()));
                }
            }
        }
    }

    private void processAlerts(Sequence sequence, Detection detection, List<Alert> alerts) {
        Stage active = findActiveStage(sequence);
        if (active == null) return;

        for (Alert alert : alerts) {
            if (alert.isActive() && alert.getPlate().equals(detection.plate()) && alert.getTriggerAnalyticsId() != detection.analyticsId()) {
                alert.setActive(false);
            }
        }

        for (AlertRuleConfig rule : appConfig.getAlerts()) {
            if (rule.getTrigger().getAnalyticsId() != detection.analyticsId()) continue;
            if (rule.getTrigger().getDirection() != null && (detection.direction() == null || angularDistance(rule.getTrigger().getDirection(), detection.direction()) > 90)) {
                continue;
            }
            boolean exists = alerts.stream().anyMatch(a -> a.isActive() && a.getPlate().equals(detection.plate()) && a.getTriggerAnalyticsId() == detection.analyticsId());
            if (exists) continue;

            Alert alert = new Alert();
            alert.setPlate(detection.plate());
            alert.setTriggerAnalyticsId(detection.analyticsId());
            alert.setMessage(rule.getMessage());
            alert.setTimeoutSeconds(rule.getSendTimeOutMinutes() * 60);
            alert.setCreatedAtUtc(detection.createdAtUtc());
            alert.setActive(true);
            alerts.add(alert);
            active.getAlerts().add(rule.getMessage());
        }
    }

    private void tickAlerts(List<Alert> alerts, LocalDateTime nowUtc) {
        for (Alert alert : alerts) {
            if (!alert.isActive()) continue;
            if (alert.getCreatedAtUtc() == null) continue;
            int elapsed = secondsBetween(alert.getCreatedAtUtc(), nowUtc);
            int remaining = alert.getTimeoutSeconds() - elapsed;
            alert.setTimeoutSeconds(Math.max(remaining, 0));
            if (remaining <= 0) {
                alertSender.send(alert.getPlate() + ": " + alert.getMessage());
                alert.setActive(false);
            }
        }
    }

    private void tickTransitionalCandidates(Iterable<Sequence> sequences, int elapsedSeconds) {
        if (elapsedSeconds <= 0) return;
        for (Sequence sequence : sequences) {
            for (Stage stage : sequence.getStages()) {
                if (!stage.isActive() || stage.getType() != StageType.TRANSITIONAL || stage.getTimeoutSeconds() <= 0) continue;
                int next = stage.getTimeoutSeconds() - elapsedSeconds;
                stage.setTimeoutSeconds(Math.max(next, 0));
                if (next <= 0 && !stage.isFull()) {
                    stage.setFull(true);
                }
            }
        }
    }

    private List<Sequence> flatten(Map<String, List<Sequence>> byPlate) {
        List<Sequence> result = new ArrayList<>();
        for (List<Sequence> sequences : byPlate.values()) {
            result.addAll(sequences);
        }
        return result;
    }

    private Sequence getOrCreateOpenSequence(Map<String, List<Sequence>> byPlate, String plate, LocalDateTime timestamp) {
        List<Sequence> sequences = byPlate.computeIfAbsent(plate, key -> new ArrayList<>());
        for (int i = sequences.size() - 1; i >= 0; i--) {
            Sequence existing = sequences.get(i);
            if (!existing.isClosed()) {
                return existing;
            }
        }
        Sequence sequence = newSequence(plate, timestamp);
        sequences.add(sequence);
        return sequence;
    }

    private int secondsBetween(LocalDateTime fromUtc, LocalDateTime toUtc) {
        if (fromUtc == null || toUtc == null || toUtc.isBefore(fromUtc)) return appConfig.getSourceRefreshSeconds();
        return (int) Duration.between(fromUtc, toUtc).getSeconds();
    }

    private record StageMatch(StageRuleConfig rule, StageType type, boolean in) {
    }
}
