package script.winnerCustomization.service.logic;
 
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.*;
import script.winnerCustomization.model.Detection;
 
import java.time.LocalDateTime;
import java.util.List;
 
import static org.junit.jupiter.api.Assertions.*;
 
class TriggerMatcherTest {
 
    private WorkflowConfig workflow;
 
    @BeforeEach
    void setUp() {
        workflow = new WorkflowConfig();
        workflow.setSequenceCloseTimeoutMinutes(2880);
 
        // Real stages
        RealStageConfig driveIn = new RealStageConfig();
        driveIn.setName("drive_in");
        driveIn.setLabel("Drive In");
        TriggerConfig driveInIn = new TriggerConfig();
        driveInIn.setType("in");
        driveInIn.setAnalyticsId(1);
        TriggerConfig driveInOut = new TriggerConfig();
        driveInOut.setType("out");
        driveInOut.setAnalyticsId(1);
        driveIn.setTriggers(List.of(driveInIn, driveInOut));
 
        RealStageConfig service = new RealStageConfig();
        service.setName("service");
        service.setLabel("Service");
        TriggerConfig serviceIn = new TriggerConfig();
        serviceIn.setType("in");
        serviceIn.setAnalyticsId(2);
        TriggerConfig serviceOut = new TriggerConfig();
        serviceOut.setType("out");
        serviceOut.setAnalyticsId(2);
        serviceOut.setDirection(180);
        service.setTriggers(List.of(serviceIn, serviceOut));
 
        RealStageConfig parking = new RealStageConfig();
        parking.setName("parking");
        parking.setLabel("Parking");
        TriggerConfig parkingIn = new TriggerConfig();
        parkingIn.setType("in");
        parkingIn.setAnalyticsId(3);
        parkingIn.setDirection(0);
        TriggerConfig parkingOut = new TriggerConfig();
        parkingOut.setType("out");
        parkingOut.setAnalyticsId(3);
        parkingOut.setDirection(180);
        parking.setTriggers(List.of(parkingIn, parkingOut));
 
        workflow.setReal(List.of(driveIn, service, parking));
 
        // Transitional stages
        TransitionalStageConfig backyard = new TransitionalStageConfig();
        backyard.setName("backyard");
        backyard.setLabel("Backyard");
        TriggerConfig backyardIn = new TriggerConfig();
        backyardIn.setType("in");
        backyardIn.setAnalyticsId(4);
        backyard.setTriggers(List.of(backyardIn));
        backyard.setAllowedAfter(List.of("service", "parking"));
        backyard.setCandidateTimeoutMinutes(5);
        backyard.setSequenceCloseTimeoutOverrideMinutes(0);
 
        workflow.setTransitional(List.of(backyard));
 
        // Single camera
        SingleCameraConfig post1 = new SingleCameraConfig();
        post1.setName("post_1");
        post1.setLabel("Post 1");
        post1.setAnalyticsId(6);
 
        workflow.setSingleCamera(List.of(post1));
    }
 
    private Detection makeDetection(int analyticsId, Integer direction) {
        Detection d = new Detection();
        d.setPlateNumber("ABC123");
        d.setAnalyticsId(analyticsId);
        d.setDirection(direction);
        d.setCreatedAt(LocalDateTime.of(2025, 1, 1, 12, 0, 0));
        return d;
    }
 
    @Test
    void findPrimaryMatch_inTrigger_driveIn() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(1, null);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNotNull(result);
        assertEquals("drive_in", result.stageName);
        // With no direction specified on both triggers for analyticsId=1,
        // out takes priority
        assertEquals("out", result.triggerType);
    }
 
    @Test
    void findPrimaryMatch_serviceInNoDirection() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        // analyticsId=2, direction=0 -> matches service "in" (no direction)
        // and service "out" (direction=180, detection=0 -> distance=180, >90) -> no match
        Detection d = makeDetection(2, 0);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNotNull(result);
        assertEquals("service", result.stageName);
        assertEquals("in", result.triggerType);
    }
 
    @Test
    void findPrimaryMatch_serviceOutDirection180() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(2, 180);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNotNull(result);
        assertEquals("service", result.stageName);
        // Both in (no direction) and out (direction=180) match.
        // Out takes priority.
        assertEquals("out", result.triggerType);
    }
 
    @Test
    void findPrimaryMatch_parkingInDirection0() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(3, 0);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNotNull(result);
        assertEquals("parking", result.stageName);
        assertEquals("in", result.triggerType);
    }
 
    @Test
    void findPrimaryMatch_parkingOutDirection180() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(3, 180);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNotNull(result);
        assertEquals("parking", result.stageName);
        assertEquals("out", result.triggerType);
    }
 
    @Test
    void findPrimaryMatch_noMatch() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(999, null);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNull(result);
    }
 
    @Test
    void findSingleCameraMatches() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(6, null);
        List<TriggerMatcher.MatchResult> results = matcher.findSingleCameraMatches(d);
 
        assertEquals(1, results.size());
        assertEquals("post_1", results.get(0).stageName);
    }
 
    @Test
    void findPrimaryMatch_transitionalInTrigger() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
        Detection d = makeDetection(4, null);
        TriggerMatcher.MatchResult result = matcher.findPrimaryMatch(d);
 
        assertNotNull(result);
        assertEquals("backyard", result.stageName);
        assertEquals("transitional", result.stageType);
        assertEquals("in", result.triggerType);
        assertNotNull(result.transitionalConfig);
    }
 
    @Test
    void matchesAlertTrigger_withDirection() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
 
        AlertTriggerConfig alertTrigger = new AlertTriggerConfig();
        alertTrigger.setAnalyticsId(1);
        alertTrigger.setDirection(200);
 
        Detection matching = makeDetection(1, 200);
        assertTrue(matcher.matchesAlertTrigger(matching, alertTrigger));
 
        Detection tooFar = makeDetection(1, 0);
        assertFalse(matcher.matchesAlertTrigger(tooFar, alertTrigger));
 
        Detection wrongCamera = makeDetection(2, 200);
        assertFalse(matcher.matchesAlertTrigger(wrongCamera, alertTrigger));
    }
 
    @Test
    void matchesAlertTrigger_noDirection() {
        TriggerMatcher matcher = new TriggerMatcher(workflow);
 
        AlertTriggerConfig alertTrigger = new AlertTriggerConfig();
        alertTrigger.setAnalyticsId(2);
        // No direction set
 
        Detection d = makeDetection(2, 45);
        assertTrue(matcher.matchesAlertTrigger(d, alertTrigger));
 
        Detection noDir = makeDetection(2, null);
        assertTrue(matcher.matchesAlertTrigger(noDir, alertTrigger));
    }
}