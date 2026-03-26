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
import java.time.ZoneOffset;
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

        Map<String, Sequence> byPlate = new HashMap<>();
        List<Alert> alerts = new ArrayList<>();
        LocalDateTime lastProcessed = null;

        for (Detection detection : ordered) {
            lastProcessed = detection.createdAtUtc();
            Sequence sequence = byPlate.computeIfAbsent(detection.plate(), plate -> newSequence(plate, detection.createdAtUtc()));
            sequence.setLastDetection(detection.createdAtUtc());
            applyDetection(sequence, detection);
            processAlerts(sequence, detection, alerts);
        }

        closeExpiredSequences(byPlate.values(), nowUtc);
        recalculateDurations(byPlate.values(), nowUtc);
        tickAlerts(alerts, appConfig.getSourceRefreshSeconds());

        return new EngineSnapshot(new ArrayList<>(byPlate.values()), alerts, lastProcessed);
    }

    private Sequence newSequence(String plate, LocalDateTime timestamp) {
        Sequence sequence = new Sequence();
        sequence.setPlate(plate);
        sequence.setLastDetection(timestamp);
        return sequence;
    }

    private void applyDetection(Sequence sequence, Detection detection) {
        if (sequence.isClosed()) return;

        Stage active = findActiveStage(sequence);
        StageMatch match = findMatch(detection);
        if (match == null) return;

        if (match.in) {
            if (active != null && active.getName().equals(match.rule.getName()) && active.getInTime() != null && active.getOutTime() == null) {
                return;
            }
            if (active != null) {
                closeCurrentStageForNewIn(active, detection.createdAtUtc());
            }
            Stage newStage = newStage(match.rule, match.type, detection.plate());
            newStage.setInTime(detection.createdAtUtc());
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
            partial.setFull(false);
            partial.setActive(true);
            sequence.getStages().add(partial);
            return;
        }

        if (active.getName().equals(match.rule.getName())) {
            active.setOutTime(detection.createdAtUtc());
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
        partial.setFull(false);
        partial.setActive(true);
        sequence.getStages().add(partial);
    }

    private void closeCurrentStageForNewIn(Stage active, LocalDateTime nextIn) {
        if (active.getType() == StageType.TRANSITIONAL && active.getInTime() != null && active.getOutTime() == null) {
            active.setOutTime(nextIn.minusSeconds(1));
        }
        active.setActive(false);
        if (active.getType() == StageType.SINGLE_CAMERA && active.getOutTime() == null) {
            active.setOutTime(nextIn.minusSeconds(1));
        }
    }

    private Stage newStage(StageRuleConfig rule, StageType stageType, String plate) {
        Stage stage = new Stage();
        stage.setName(rule.getName());
        stage.setLabel(rule.getLabel());
        stage.setType(stageType);
        stage.setPlate(plate);
        stage.setTimeoutSeconds(stageType == StageType.TRANSITIONAL ? rule.getCandidateTimeoutMinutes() * 60 : 0);
        return stage;
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
            long minutes = Duration.between(sequence.getLastDetection(), nowUtc).toMinutes();
            if (minutes >= appConfig.getWorkflow().getSequenceCloseTimeoutMinutes()) {
                Stage active = findActiveStage(sequence);
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
            alert.setActive(true);
            alerts.add(alert);
            active.getAlerts().add(rule.getMessage());
        }
    }

    private void tickAlerts(List<Alert> alerts, int refreshSeconds) {
        for (Alert alert : alerts) {
            if (!alert.isActive()) continue;
            alert.setTimeoutSeconds(alert.getTimeoutSeconds() - refreshSeconds);
            if (alert.getTimeoutSeconds() <= 0) {
                alertSender.send(alert.getPlate() + ": " + alert.getMessage());
                alert.setActive(false);
            }
        }
    }

    private record StageMatch(StageRuleConfig rule, StageType type, boolean in) {
    }
}
