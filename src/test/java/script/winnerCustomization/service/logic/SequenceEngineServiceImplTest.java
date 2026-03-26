package script.winnerCustomization.service.logic;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.AlertRuleConfig;
import script.winnerCustomization.config.AppConfig;
import script.winnerCustomization.config.StageRuleConfig;
import script.winnerCustomization.config.TriggerConfig;
import script.winnerCustomization.config.WorkflowConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.Sequence;
import script.winnerCustomization.model.Stage;
import script.winnerCustomization.model.StageType;
import script.winnerCustomization.repository.SqlFileSourceDetectionRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SequenceEngineServiceImplTest {

    @Test
    void createsPartialWhenFirstEventIsOutAndPromotesOnSecondOut() {
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(config(), text -> {});
        List<Detection> detections = List.of(
            new Detection(1, "AA2", 1, 180, LocalDateTime.of(2026, 3, 1, 11, 0)),
            new Detection(2, "AA2", 1, 182, LocalDateTime.of(2026, 3, 1, 11, 1))
        );

        var snapshot = service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 11, 2));
        Stage stage = snapshot.sequences().getFirst().getStages().getFirst();
        assertNotNull(stage.getInTime());
        assertNotNull(stage.getOutTime());
        assertTrue(stage.isFull());
    }

    @Test
    void deduplicatesConsecutiveInOnSameActiveStage() {
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(config(), text -> {});
        List<Detection> detections = List.of(
            new Detection(1, "AA1", 1, 0, LocalDateTime.of(2026, 3, 1, 10, 0)),
            new Detection(2, "AA1", 1, 2, LocalDateTime.of(2026, 3, 1, 10, 1))
        );

        var snapshot = service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 10, 2));
        assertEquals(1, snapshot.sequences().getFirst().getStages().size());
    }

    @Test
    void closesSingleCameraUsingLastDetectionTime() {
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(configWithTwoSingleCameras(), text -> {});
        List<Detection> detections = List.of(
            new Detection(1, "AA4", 5, null, LocalDateTime.of(2026, 3, 1, 10, 0)),
            new Detection(2, "AA4", 5, null, LocalDateTime.of(2026, 3, 1, 10, 3)),
            new Detection(3, "AA4", 6, null, LocalDateTime.of(2026, 3, 1, 10, 10))
        );

        var snapshot = service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 10, 11));
        Stage first = snapshot.sequences().getFirst().getStages().getFirst();
        assertEquals(LocalDateTime.of(2026, 3, 1, 10, 3), first.getOutTime());
    }

    @Test
    void supportsIncrementalAlertTimeoutByRealElapsedTime() {
        List<String> sent = new ArrayList<>();
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(configWithAlerts(), sent::add);

        var initial = service.rebuild(List.of(new Detection(1, "AA3", 1, 0, LocalDateTime.of(2026, 3, 1, 10, 0))), LocalDateTime.of(2026, 3, 1, 10, 0, 10));
        var after = service.applyIncremental(initial.sequences(), initial.alerts(), List.of(), LocalDateTime.of(2026, 3, 1, 10, 0, 10), LocalDateTime.of(2026, 3, 1, 10, 1, 1));

        assertTrue(sent.stream().anyMatch(m -> m.contains("AA3")));
        assertTrue(after.alerts().stream().noneMatch(a -> a.isActive() && "AA3".equals(a.getPlate())));
    }

    @Test
    void createsTransitionalCandidateAfterAllowedRealOut() {
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(configWithTransitional(), text -> {});
        List<Detection> detections = List.of(new Detection(1, "AA5", 1, 180, LocalDateTime.of(2026, 3, 1, 12, 0)));

        var snapshot = service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 12, 1));
        Sequence sequence = snapshot.sequences().getFirst();
        assertTrue(sequence.getStages().stream().anyMatch(s -> s.getType() == StageType.TRANSITIONAL));
    }

    @Test
    void loadsRealSqlDataWithoutErrors() {
        SqlFileSourceDetectionRepository repo = new SqlFileSourceDetectionRepository();
        List<Detection> detections = repo.findAll();
        assertFalse(detections.isEmpty());

        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(config(), text -> {});
        var snapshot = service.rebuild(detections.subList(0, Math.min(200, detections.size())), LocalDateTime.of(2026, 3, 26, 0, 0));
        assertFalse(snapshot.sequences().isEmpty());
    }

    private AppConfig config() {
        AppConfig c = new AppConfig();
        c.setSourceRefreshSeconds(60);
        WorkflowConfig wf = new WorkflowConfig();
        wf.setSequenceCloseTimeoutMinutes(120);

        StageRuleConfig real = new StageRuleConfig();
        real.setName("drive_in");
        real.setLabel("Drive In");
        TriggerConfig in = new TriggerConfig();
        in.setType("in");
        in.setAnalyticsId(1);
        in.setDirection(0);
        TriggerConfig out = new TriggerConfig();
        out.setType("out");
        out.setAnalyticsId(1);
        out.setDirection(180);
        real.setTriggers(List.of(in, out));
        wf.setReal(List.of(real));

        StageRuleConfig single = new StageRuleConfig();
        single.setName("post_1");
        single.setLabel("Post 1");
        single.setAnalyticsId(5);
        wf.setSingleCamera(List.of(single));

        c.setWorkflow(wf);
        return c;
    }

    private AppConfig configWithTwoSingleCameras() {
        AppConfig c = config();
        StageRuleConfig single2 = new StageRuleConfig();
        single2.setName("post_2");
        single2.setLabel("Post 2");
        single2.setAnalyticsId(6);
        c.getWorkflow().setSingleCamera(List.of(c.getWorkflow().getSingleCamera().getFirst(), single2));
        return c;
    }

    private AppConfig configWithAlerts() {
        AppConfig c = config();
        AlertRuleConfig alert = new AlertRuleConfig();
        TriggerConfig trigger = new TriggerConfig();
        trigger.setAnalyticsId(1);
        alert.setTrigger(trigger);
        alert.setMessage("test");
        alert.setSendTimeOutMinutes(1);
        c.setAlerts(List.of(alert));
        return c;
    }

    private AppConfig configWithTransitional() {
        AppConfig c = config();
        StageRuleConfig transitional = new StageRuleConfig();
        transitional.setName("between");
        transitional.setLabel("Between");
        transitional.setAllowedAfter(List.of("drive_in"));
        transitional.setCandidateTimeoutMinutes(2);
        c.getWorkflow().setTransitional(List.of(transitional));
        return c;
    }
}
