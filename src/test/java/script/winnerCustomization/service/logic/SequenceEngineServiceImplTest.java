package script.winnerCustomization.service.logic;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.alerts.AlertSender;
import script.winnerCustomization.config.AlertRuleConfig;
import script.winnerCustomization.config.AppConfig;
import script.winnerCustomization.config.StageRuleConfig;
import script.winnerCustomization.config.TriggerConfig;
import script.winnerCustomization.config.WorkflowConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.Stage;
import script.winnerCustomization.model.StageType;
import script.winnerCustomization.repository.SqlFileSourceDetectionRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SequenceEngineServiceImplTest {

    @Test
    void buildsRealStageFromInThenOut() {
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(config(), text -> {});
        List<Detection> detections = List.of(
            new Detection(1, "AA1", 1, 0, LocalDateTime.of(2026, 3, 1, 10, 0)),
            new Detection(2, "AA1", 1, 180, LocalDateTime.of(2026, 3, 1, 10, 5))
        );

        var snapshot = service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 10, 6));
        Stage stage = snapshot.sequences().getFirst().getStages().getFirst();
        assertEquals(StageType.REAL, stage.getType());
        assertNotNull(stage.getInTime());
        assertNotNull(stage.getOutTime());
        assertEquals(300, stage.getDuration().getSeconds());
    }

    @Test
    void createsPartialWhenFirstEventIsOut() {
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(config(), text -> {});
        List<Detection> detections = List.of(new Detection(1, "AA2", 1, 180, LocalDateTime.of(2026, 3, 1, 11, 0)));

        var snapshot = service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 11, 1));
        Stage stage = snapshot.sequences().getFirst().getStages().getFirst();
        assertNull(stage.getInTime());
        assertNotNull(stage.getOutTime());
        assertFalse(stage.isFull());
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

    @Test
    void sendsAlertWhenTimeoutExpires() {
        List<String> sent = new ArrayList<>();
        SequenceEngineServiceImpl service = new SequenceEngineServiceImpl(configWithAlerts(), sent::add);
        List<Detection> detections = List.of(new Detection(1, "AA3", 1, 0, LocalDateTime.of(2026, 3, 1, 10, 0)));
        service.rebuild(detections, LocalDateTime.of(2026, 3, 1, 10, 1));
        assertFalse(sent.isEmpty());
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
}
