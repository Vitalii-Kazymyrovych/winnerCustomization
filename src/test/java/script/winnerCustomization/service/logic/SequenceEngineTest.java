package script.winnerCustomization.service.logic;
 
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.*;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.model.PlateSequence;
import script.winnerCustomization.model.Stage;
 
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
 
import static org.junit.jupiter.api.Assertions.*;
 
class SequenceEngineTest {
 
    private SequenceEngineServiceImpl engine;
    private ConfigLoader configLoader;
    private static final LocalDateTime T0 = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
 
    @BeforeEach
    void setUp() {
        AppConfig config = buildTestConfig();
        configLoader = new ConfigLoader();
        // Use reflection-free approach: set config directly
        setConfig(configLoader, config);
        engine = new SequenceEngineServiceImpl(configLoader);
    }
 
    private void setConfig(ConfigLoader loader, AppConfig config) {
        try {
            var field = ConfigLoader.class.getDeclaredField("config");
            field.setAccessible(true);
            field.set(loader, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
 
    private AppConfig buildTestConfig() {
        AppConfig config = new AppConfig();
 
        DatabaseConfig db = new DatabaseConfig();
        db.setHost("localhost");
        db.setPort(5432);
        config.setDatabase(db);
        config.setSourceRefreshSeconds(10);
        config.setReportsDir("./test-reports");
 
        MessagingConfig msg = new MessagingConfig();
        msg.setEnabled(false);
        config.setMessaging(msg);
 
        WorkflowConfig wf = new WorkflowConfig();
        wf.setSequenceCloseTimeoutMinutes(999999); // large value to prevent timeout in tests
 
        // Real stage: drive_in (analyticsId=1, no direction)
        RealStageConfig driveIn = new RealStageConfig();
        driveIn.setName("drive_in");
        driveIn.setLabel("Drive In");
        TriggerConfig diIn = new TriggerConfig();
        diIn.setType("in");
        diIn.setAnalyticsId(1);
        diIn.setDirection(0);
        TriggerConfig diOut = new TriggerConfig();
        diOut.setType("out");
        diOut.setAnalyticsId(1);
        diOut.setDirection(180);
        driveIn.setTriggers(List.of(diIn, diOut));
 
        // Real stage: service (analyticsId=2)
        RealStageConfig service = new RealStageConfig();
        service.setName("service");
        service.setLabel("Service");
        TriggerConfig sIn = new TriggerConfig();
        sIn.setType("in");
        sIn.setAnalyticsId(2);
        sIn.setDirection(0);
        TriggerConfig sOut = new TriggerConfig();
        sOut.setType("out");
        sOut.setAnalyticsId(2);
        sOut.setDirection(180);
        service.setTriggers(List.of(sIn, sOut));
 
        wf.setReal(List.of(driveIn, service));
 
        // Transitional: backyard (allowedAfter: service)
        TransitionalStageConfig backyard = new TransitionalStageConfig();
        backyard.setName("backyard");
        backyard.setLabel("Backyard");
        TriggerConfig bIn = new TriggerConfig();
        bIn.setType("in");
        bIn.setAnalyticsId(4);
        backyard.setTriggers(List.of(bIn));
        backyard.setAllowedAfter(List.of("service"));
        backyard.setCandidateTimeoutMinutes(5);
        backyard.setSequenceCloseTimeoutOverrideMinutes(0);
 
        wf.setTransitional(List.of(backyard));
 
        // Single camera: post_1 (analyticsId=6)
        SingleCameraConfig post1 = new SingleCameraConfig();
        post1.setName("post_1");
        post1.setLabel("Post 1");
        post1.setAnalyticsId(6);
        wf.setSingleCamera(List.of(post1));
 
        config.setWorkflow(wf);
 
        // Alert config
        AlertConfig alert = new AlertConfig();
        AlertTriggerConfig at = new AlertTriggerConfig();
        at.setAnalyticsId(1);
        at.setDirection(180);
        alert.setTrigger(at);
        alert.setMessage("Vehicle too long on Drive In");
        alert.setSendTimeOutMinutes(15);
        config.setAlerts(List.of(alert));
 
        return config;
    }
 
    private Detection makeDetection(String plate, int analyticsId, Integer direction, LocalDateTime time) {
        Detection d = new Detection();
        d.setPlateNumber(plate);
        d.setAnalyticsId(analyticsId);
        d.setDirection(direction);
        d.setCreatedAt(time);
        return d;
    }
 
    // ========== BASIC SEQUENCE TESTS ==========
 
    @Test
    void processDetection_inTrigger_createsSequenceAndStage() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0)
        );
        engine.processDetections(detections);
 
        List<PlateSequence> seqs = engine.getAllSequences();
        assertEquals(1, seqs.size());
 
        PlateSequence seq = seqs.get(0);
        assertEquals("ABC123", seq.getPlateNumber());
        assertTrue(seq.isActive());
        assertEquals(1, seq.getStages().size());
 
        Stage stage = seq.getStages().get(0);
        assertEquals("drive_in", stage.getName());
        assertTrue(stage.isActive());
        assertTrue(stage.isFull());
        assertEquals(T0, stage.getInTime());
        assertNull(stage.getOutTime());
    }
 
    @Test
    void processDetection_inThenOut_sameStage() {
        LocalDateTime t1 = T0.plusMinutes(10);
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),
                makeDetection("ABC123", 1, 180, t1)
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getStages().size());
 
        Stage stage = seq.getStages().get(0);
        assertEquals("drive_in", stage.getName());
        assertTrue(stage.isActive());
        assertEquals(T0, stage.getInTime());
        assertEquals(t1, stage.getOutTime());
    }
 
    @Test
    void processDetection_twoStages_normalFlow() {
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(10);
        LocalDateTime t3 = T0.plusMinutes(20);
 
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),        // drive_in IN
                makeDetection("ABC123", 1, 180, t1),      // drive_in OUT
                makeDetection("ABC123", 2, 0, t2),        // service IN
                makeDetection("ABC123", 2, 180, t3)       // service OUT
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        // May include candidates, filter to non-candidates
        List<Stage> stages = seq.getStages().stream()
                .filter(s -> !s.isCandidate()).toList();
 
        assertEquals(2, stages.size());
 
        Stage driveIn = stages.get(0);
        assertEquals("drive_in", driveIn.getName());
        assertFalse(driveIn.isActive()); // closed by service IN
        assertEquals(T0, driveIn.getInTime());
 
        Stage service = stages.get(1);
        assertEquals("service", service.getName());
        assertTrue(service.isActive());
        assertEquals(t2, service.getInTime());
        assertEquals(t3, service.getOutTime());
    }
 
    // ========== COLD START TESTS ==========
 
    @Test
    void processDetection_coldStart_outTrigger_createsPartial() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 180, T0) // OUT trigger as first detection
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getStages().size());
 
        Stage stage = seq.getStages().get(0);
        assertEquals("drive_in", stage.getName());
        assertFalse(stage.isFull());
        assertNull(stage.getInTime());
        assertEquals(T0, stage.getOutTime());
    }
 
    // ========== DEDUPLICATION TESTS ==========
 
    @Test
    void processDetection_duplicateIn_ignored() {
        LocalDateTime t1 = T0.plusMinutes(5);
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),
                makeDetection("ABC123", 1, 0, t1) // duplicate IN
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getStages().size());
        assertEquals(T0, seq.getStages().get(0).getInTime());
    }
 
    @Test
    void processDetection_multipleOut_overwritesOutTime() {
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(10);
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),
                makeDetection("ABC123", 1, 180, t1),
                makeDetection("ABC123", 1, 180, t2) // second OUT
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        Stage stage = seq.getStages().get(0);
        assertEquals(t2, stage.getOutTime());
    }
 
    // ========== PARTIAL STAGE TESTS ==========
 
    @Test
    void processDetection_outForDifferentStage_createsPartial() {
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(10);
 
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),        // drive_in IN
                makeDetection("ABC123", 1, 180, t1),      // drive_in OUT
                makeDetection("ABC123", 2, 180, t2)       // service OUT (no IN first)
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        List<Stage> stages = seq.getStages().stream()
                .filter(s -> !s.isCandidate()).toList();
 
        assertTrue(stages.size() >= 2);
 
        // drive_in should be closed
        Stage driveIn = stages.get(0);
        assertFalse(driveIn.isActive());
 
        // Find the service partial stage
        Stage service = stages.stream()
                .filter(s -> s.getName().equals("service")).findFirst().orElse(null);
        assertNotNull(service);
        assertFalse(service.isFull());
        assertNull(service.getInTime());
        assertEquals(t2, service.getOutTime());
    }
 
    // ========== TRANSITIONAL CANDIDATE TESTS ==========
 
    @Test
    void transitionalCandidate_createdOnAllowedAfterOut() {
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(10);
 
        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),        // service IN
                makeDetection("ABC123", 2, 180, t1)       // service OUT
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        List<Stage> candidates = seq.getCandidates();
 
        assertEquals(1, candidates.size());
        assertEquals("backyard", candidates.get(0).getName());
        assertTrue(candidates.get(0).isCandidate());
        assertEquals(5 * 60 + 1, candidates.get(0).getTimeout()); // 5 min in seconds +1 for inTime offset
    }
 
    @Test
    void transitionalCandidate_invalidatedByNewDetection() {
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(7); // within 5-min timeout
 
        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),        // service IN
                makeDetection("ABC123", 2, 180, t1),      // service OUT -> creates candidate
                makeDetection("ABC123", 1, 0, t2)         // drive_in IN -> invalidates candidate
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        List<Stage> candidates = seq.getCandidates();
        assertEquals(0, candidates.size());
    }
 
    @Test
    void transitionalCandidate_materializes() {
        LocalDateTime t1 = T0.plusMinutes(5);
 
        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),        // service IN
                makeDetection("ABC123", 2, 180, t1)       // service OUT -> creates candidate
        );
        engine.processDetections(detections);
 
        // Simulate time passing beyond candidate timeout (5 min = 300 sec)
        engine.performMaintenance(301);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        List<Stage> candidates = seq.getCandidates();
        assertEquals(0, candidates.size()); // should have materialized
 
        // Find materialized backyard stage
        Stage backyard = seq.getStages().stream()
                .filter(s -> s.getName().equals("backyard") && !s.isCandidate())
                .findFirst().orElse(null);
        assertNotNull(backyard);
        assertTrue(backyard.isFull());
    }
 
    // ========== SINGLE CAMERA TESTS ==========
 
    @Test
    void singleCamera_createsStage() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 6, null, T0)
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getStages().size());
 
        Stage stage = seq.getStages().get(0);
        assertEquals("post_1", stage.getName());
        assertEquals("singleCamera", stage.getType());
        assertEquals(T0, stage.getInTime());
        assertEquals(T0, stage.getOutTime());
    }
 
    @Test
    void singleCamera_updatesOutTimeOnConsecutiveDetections() {
        LocalDateTime t1 = T0.plusMinutes(5);
        List<Detection> detections = List.of(
                makeDetection("ABC123", 6, null, T0),
                makeDetection("ABC123", 6, null, t1)
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getStages().size());
 
        Stage stage = seq.getStages().get(0);
        assertEquals(T0, stage.getInTime());
        assertEquals(t1, stage.getOutTime());
    }
 
    // ========== ALERT TESTS ==========
 
    @Test
    void alert_createdWhenTriggerMatches() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),        // drive_in IN (starts stage)
                makeDetection("ABC123", 1, 180, T0.plusMinutes(1))  // matches alert trigger
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        Stage driveIn = seq.getStages().get(0);
        assertEquals(1, driveIn.getAlerts().size());
        assertEquals("Vehicle too long on Drive In", driveIn.getAlerts().get(0).getMessage());
        assertTrue(driveIn.getAlerts().get(0).isActive());
    }
 
    @Test
    void alert_deactivatedOnDifferentCamera() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),                    // drive_in IN
                makeDetection("ABC123", 1, 180, T0.plusMinutes(1)),    // alert trigger
                makeDetection("ABC123", 2, 0, T0.plusMinutes(2))       // different camera
        );
        engine.processDetections(detections);
 
        PlateSequence seq = engine.getAllSequences().get(0);
        Stage driveIn = seq.getStages().get(0);
        assertFalse(driveIn.getAlerts().get(0).isActive());
    }
 
    @Test
    void alert_sentAfterTimeout() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),
                makeDetection("ABC123", 1, 180, T0.plusMinutes(1))
        );
        engine.processDetections(detections);
 
        // Simulate 15 minutes passing (alert timeout)
        engine.performMaintenance(15 * 60 + 1);
 
        List<script.winnerCustomization.model.AlertRecord> pending = engine.getPendingAlertSends();
        assertEquals(1, pending.size());
        assertEquals("Vehicle too long on Drive In", pending.get(0).getMessage());
    }
 
    // ========== MULTIPLE PLATES ==========
 
    @Test
    void multiplePlates_independentSequences() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),
                makeDetection("XYZ789", 2, 0, T0.plusSeconds(1))
        );
        engine.processDetections(detections);
 
        List<PlateSequence> seqs = engine.getAllSequences();
        assertEquals(2, seqs.size());
    }
 
    // ========== MAINTENANCE ==========
 
    @Test
    void maintenance_recalculatesDuration() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),
                makeDetection("ABC123", 1, 180, T0.plusMinutes(10))
        );
        engine.processDetections(detections);
 
        engine.performMaintenance(0);
 
        Stage stage = engine.getAllSequences().get(0).getStages().get(0);
        assertNotNull(stage.getDurationSeconds());
    }
 
    @Test
    void reset_clearsAllState() {
        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0)
        );
        engine.processDetections(detections);
        assertEquals(1, engine.getAllSequences().size());
 
        engine.reset();
        assertEquals(0, engine.getAllSequences().size());
    }
 
    // ========== DURATION CALCULATION TESTS ==========

    @Test
    void closedStage_hasDurationSet_whenClosedByNextStage() {
        // drive_in IN at T0, drive_in OUT at T0+10, service IN at T0+20
        // drive_in stage is closed by service IN — duration must be set immediately
        LocalDateTime t1 = T0.plusMinutes(10);
        LocalDateTime t2 = T0.plusMinutes(20);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0),        // drive_in IN
                makeDetection("ABC123", 1, 180, t1),      // drive_in OUT
                makeDetection("ABC123", 2, 0, t2)         // service IN — closes drive_in
        );
        engine.processDetections(detections);

        PlateSequence seq = engine.getAllSequences().get(0);
        Stage driveIn = seq.getStages().stream()
                .filter(s -> s.getName().equals("drive_in")).findFirst().orElseThrow();

        assertFalse(driveIn.isActive());
        assertNotNull(driveIn.getDurationSeconds(),
                "Duration must be set when stage is closed by the next stage");
        assertEquals(Duration.between(T0, t1).getSeconds(), (long) driveIn.getDurationSeconds());
    }

    @Test
    void singleCameraStage_hasDurationSet_whenClosedByNextStage() {
        // post_1 at T0, then drive_in IN at T0+5 closes the single camera stage
        LocalDateTime t1 = T0.plusMinutes(2);
        LocalDateTime t2 = T0.plusMinutes(5);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 6, null, T0),     // post_1 first detection
                makeDetection("ABC123", 6, null, t1),     // post_1 second detection (updates outTime)
                makeDetection("ABC123", 1, 0, t2)         // drive_in IN — closes post_1
        );
        engine.processDetections(detections);

        PlateSequence seq = engine.getAllSequences().get(0);
        Stage post1 = seq.getStages().stream()
                .filter(s -> s.getName().equals("post_1")).findFirst().orElseThrow();

        assertFalse(post1.isActive());
        assertNotNull(post1.getDurationSeconds(),
                "Duration must be set for single camera stage when closed by next stage");
        assertEquals(Duration.between(T0, t1).getSeconds(), (long) post1.getDurationSeconds());
    }

    @Test
    void partialStagePromotion_secondOutPromotesToFull() {
        // Cold-start OUT creates partial (inTime=null, outTime=T0, full=false).
        // Second OUT on the same stage must promote it: inTime=T0, outTime=t1, full=true.
        LocalDateTime t1 = T0.plusMinutes(5);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 180, T0),   // drive_in OUT — cold-start partial
                makeDetection("ABC123", 1, 180, t1)    // drive_in OUT — promotes to full
        );
        engine.processDetections(detections);

        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getStages().size());

        Stage stage = seq.getStages().get(0);
        assertEquals("drive_in", stage.getName());
        assertTrue(stage.isFull(), "Stage must be promoted to full");
        assertEquals(T0, stage.getInTime(), "inTime must be the first OUT timestamp");
        assertEquals(t1, stage.getOutTime(), "outTime must be the second OUT timestamp");
    }

    @Test
    void partialStagePropmotion_doesNotAddNullToStagesList() {
        // Second OUT for same partial stage promotes it to full — must not add null entry
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(10);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 180, T0),      // drive_in OUT (cold-start partial)
                makeDetection("ABC123", 1, 180, t1)       // second drive_in OUT — promotes to full
        );
        engine.processDetections(detections);

        PlateSequence seq = engine.getAllSequences().get(0);
        boolean hasNull = seq.getStages().stream().anyMatch(s -> s == null);
        assertFalse(hasNull, "Stages list must not contain null entries after partial promotion");
        assertEquals(1, seq.getStages().size(),
                "Exactly one stage after two consecutive OUTs on same stage");
    }

    // ========== TRANSITIONAL IN-TRIGGER TESTS ==========

    @Test
    void transitionalInTrigger_createsCandidateNotLiveStage() {
        // service IN then backyard in-trigger (analyticsId=4).
        // Must create a candidate (candidate=true, full=false), NOT a live stage.
        // Previous service stage must remain active.
        LocalDateTime t1 = T0.plusMinutes(5);

        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),        // service IN
                makeDetection("ABC123", 4, null, t1)      // backyard in-trigger
        ));

        PlateSequence seq = engine.getAllSequences().get(0);

        // Service stage must still be active
        Stage service = seq.getStages().stream()
                .filter(s -> s.getName().equals("service"))
                .findFirst().orElseThrow(() -> new AssertionError("service stage missing"));
        assertTrue(service.isActive(), "Previous real stage must remain active after transitional in-trigger");

        // Backyard must exist as a candidate
        List<Stage> candidates = seq.getCandidates();
        assertEquals(1, candidates.size(), "Exactly one candidate must be created");
        Stage backyard = candidates.get(0);
        assertEquals("backyard", backyard.getName());
        assertTrue(backyard.isCandidate());
        assertFalse(backyard.isFull());
        assertEquals(t1, backyard.getInTime());
        assertEquals(5 * 60 + 1, backyard.getTimeout());
    }

    @Test
    void transitionalInTrigger_dedup_noDuplicateCandidate() {
        // Two consecutive backyard in-triggers: only one candidate must exist.
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(6);

        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 4, null, t1),   // first backyard in-trigger
                makeDetection("ABC123", 4, null, t2)    // duplicate — must be ignored
        ));

        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getCandidates().size(),
                "Duplicate transitional in-trigger must not create a second candidate");
    }

    // ========== TRANSITIONAL OVERRIDE=0 TIMEOUT TESTS ==========

    @Test
    void transitionalOverride0_closedByMaintenance_viaInTimePath() {
        // backyard has sequenceCloseTimeoutOverrideMinutes=0.
        // Use default (999999 min) so materialization does NOT immediately trigger close.
        // Then switch to a small timeout: sequence must close because inTime (T0+5m) is
        // far enough in the past — i.e. the override==0 code path measured from inTime fires.
        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 2, 180, T0.plusMinutes(5))
        ));

        // Materialize backyard candidate (no close yet — global timeout still 999999 min)
        engine.performMaintenance(302);

        PlateSequence seq = engine.getAllSequences().get(0);
        assertTrue(seq.isActive(), "Sequence must still be active after materialization");
        Stage backyard = seq.getStages().stream()
                .filter(s -> s.getName().equals("backyard") && !s.isCandidate())
                .findFirst().orElseThrow(() -> new AssertionError("backyard not materialized"));
        assertEquals(0, backyard.getSequenceCloseTimeoutOverrideMinutes(),
                "backyard must have override=0");

        // Lower global timeout to 10 min — inTime is far in the past, so must close now
        setSequenceCloseTimeout(10);
        engine.performMaintenance(1);

        long activeCount = engine.getAllSequences().stream().filter(PlateSequence::isActive).count();
        assertEquals(0, activeCount,
                "Sequence with transitional override=0 must close when global timeout is exceeded from inTime");
    }

    // ========== CANDIDATE INVALIDATION TESTS ==========

    @Test
    void candidate_invalidatedByAnyDetection_exceptRepeatOutSameStage() {
        // Create candidate: service OUT creates backyard candidate
        LocalDateTime t1 = T0.plusMinutes(5);
        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 2, 180, t1)
        ));
        assertEquals(1, engine.getAllSequences().get(0).getCandidates().size(),
                "Candidate must exist before test");

        // An unrelated detection (drive_in IN) must invalidate the candidate
        engine.processDetections(List.of(makeDetection("ABC123", 1, 0, t1.plusMinutes(1))));

        assertEquals(0, engine.getAllSequences().get(0).getCandidates().size(),
                "Candidate must be invalidated by an unrelated detection");
    }

    @Test
    void candidate_notInvalidated_byRepeatOutSameStage() {
        // Create candidate: service OUT creates backyard candidate
        LocalDateTime t1 = T0.plusMinutes(5);
        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 2, 180, t1)
        ));
        assertEquals(1, engine.getAllSequences().get(0).getCandidates().size(),
                "Candidate must exist before test");

        // A second OUT for the same active stage must NOT invalidate the candidate
        engine.processDetections(List.of(makeDetection("ABC123", 2, 180, t1.plusMinutes(1))));

        assertEquals(1, engine.getAllSequences().get(0).getCandidates().size(),
                "Candidate must survive a repeated out for the same active stage");
    }

    // ========== LAST DETECTION TIME TESTS ==========

    @Test
    void lastDetectionTime_notUpdated_forUnknownTrigger() {
        // Seed a known detection so a sequence exists with a known lastDetectionTime
        engine.processDetections(List.of(makeDetection("ABC123", 1, 0, T0)));

        PlateSequence seq = engine.getAllSequences().get(0);
        LocalDateTime before = seq.getLastDetectionTime();

        // analyticsId=99 matches nothing — must not update lastDetectionTime
        LocalDateTime t1 = T0.plusMinutes(5);
        engine.processDetections(List.of(makeDetection("ABC123", 99, null, t1)));

        assertEquals(before, seq.getLastDetectionTime(),
                "lastDetectionTime must not change for a detection that matches no trigger");
    }

    @Test
    void lastDetectionTime_updated_forKnownTrigger() {
        engine.processDetections(List.of(makeDetection("ABC123", 1, 0, T0)));

        LocalDateTime t1 = T0.plusMinutes(5);
        engine.processDetections(List.of(makeDetection("ABC123", 1, 180, t1)));

        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(t1, seq.getLastDetectionTime(),
                "lastDetectionTime must be updated for a detection that matches a known trigger");
    }

    // ========== HISTORICAL TRANSITIONAL INSERT ==========
 
    // ========== BUG 1: SEQUENCE CLOSE TIMEOUT ==========

    @Test
    void sequenceClose_historicalGap_createsTwoSequences() {
        // Plate seen at T0, then again at T0 + (timeout + 1) minutes — should produce two sequences
        int timeoutMinutes = 10; // override in config for this test
        setSequenceCloseTimeout(timeoutMinutes);

        LocalDateTime t1 = T0;
        LocalDateTime t2 = T0.plusMinutes(timeoutMinutes + 1); // gap > timeout

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, t1),   // drive_in IN — first visit
                makeDetection("ABC123", 1, 0, t2)    // drive_in IN — second visit after timeout
        );
        engine.processDetections(detections);

        List<PlateSequence> seqs = engine.getAllSequences();
        assertEquals(2, seqs.size(), "Gap exceeding sequenceCloseTimeoutMinutes must produce two sequences");

        long closedCount = seqs.stream().filter(s -> !s.isActive()).count();
        long activeCount = seqs.stream().filter(PlateSequence::isActive).count();
        assertEquals(1, closedCount, "First sequence must be closed");
        assertEquals(1, activeCount, "Second sequence must be active");
    }

    @Test
    void sequenceClose_historicalGapWithinTimeout_oneSequence() {
        int timeoutMinutes = 10;
        setSequenceCloseTimeout(timeoutMinutes);

        LocalDateTime t1 = T0;
        LocalDateTime t2 = T0.plusMinutes(timeoutMinutes - 1); // gap < timeout

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, t1),
                makeDetection("ABC123", 1, 0, t2)
        );
        engine.processDetections(detections);

        assertEquals(1, engine.getAllSequences().size(), "Gap within timeout must stay as one sequence");
    }

    @Test
    void sequenceClose_newlyClosedTracked_andClearedAfterClear() {
        int timeoutMinutes = 10;
        setSequenceCloseTimeout(timeoutMinutes);

        LocalDateTime t1 = T0;
        LocalDateTime t2 = T0.plusMinutes(timeoutMinutes + 1);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, t1),
                makeDetection("ABC123", 1, 0, t2)
        );
        engine.processDetections(detections);

        List<PlateSequence> newlyClosed = engine.getNewlyClosedSequences();
        assertEquals(1, newlyClosed.size(), "Closed sequence must appear in getNewlyClosedSequences()");
        assertFalse(newlyClosed.get(0).isActive());

        engine.clearNewlyClosedSequences();
        assertEquals(0, engine.getNewlyClosedSequences().size(), "List must be empty after clear");
    }

    @Test
    void sequenceClose_maintenanceTimeout_appearsInNewlyClosed() {
        int timeoutMinutes = 10;
        setSequenceCloseTimeout(timeoutMinutes);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 1, 0, T0)
        );
        engine.processDetections(detections);
        assertEquals(0, engine.getNewlyClosedSequences().size());

        // Advance time beyond timeout via maintenance
        engine.performMaintenance(timeoutMinutes * 60 + 60); // elapsedSeconds > timeout

        List<PlateSequence> newlyClosed = engine.getNewlyClosedSequences();
        assertEquals(1, newlyClosed.size(), "Timed-out sequence must appear in getNewlyClosedSequences()");
        assertFalse(newlyClosed.get(0).isActive());
    }

    // ========== BUG 2: TRANSITIONAL MATERIALIZATION TIMING ==========

    @Test
    void transitionalCandidate_materializes_durationAtLeastCandidateTimeout() {
        // After exactly candidateTimeoutMinutes * 60 + 1 poll-seconds the candidate materializes.
        // Duration must be >= candidateTimeoutMinutes * 60 seconds.
        LocalDateTime t1 = T0.plusMinutes(5);

        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),        // service IN
                makeDetection("ABC123", 2, 180, t1)       // service OUT -> candidate
        );
        engine.processDetections(detections);

        // Materialize by expiring the timeout (candidateTimeoutMinutes=5 => 301 seconds initial)
        engine.performMaintenance(302);

        Stage backyard = engine.getAllSequences().get(0).getStages().stream()
                .filter(s -> s.getName().equals("backyard") && !s.isCandidate())
                .findFirst().orElseThrow(() -> new AssertionError("backyard not materialized"));

        assertNotNull(backyard.getDurationSeconds(), "Materialized transitional must have duration");
        assertTrue(backyard.getDurationSeconds() >= 5 * 60,
                "Materialized transitional duration must be >= candidateTimeoutMinutes * 60, was "
                        + backyard.getDurationSeconds());
    }

    @Test
    void insertHistoricalTransitionals_exactSecondBoundary_insertedWhenGapExceeds() {
        // Gap of candidateTimeoutMinutes * 60 + 30 seconds (5 min 30 s) — must insert transitional.
        // Previously toMinutes() truncation would give 5 > 5 = false and miss this.
        LocalDateTime serviceOut = T0.plusMinutes(5);
        LocalDateTime driveInIn = serviceOut.plusSeconds(5 * 60 + 30); // gap = 330 s > 300 s

        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 2, 180, serviceOut),
                makeDetection("ABC123", 1, 0, driveInIn)
        );
        engine.processDetections(detections);
        engine.insertHistoricalTransitionals();

        List<Stage> nonCandidates = engine.getAllSequences().get(0).getStages().stream()
                .filter(s -> !s.isCandidate()).toList();

        boolean hasBackyard = nonCandidates.stream()
                .anyMatch(s -> s.getName().equals("backyard") && "transitional".equals(s.getType()));
        assertTrue(hasBackyard, "Gap of 5 min 30s must produce a historical backyard transitional");
    }

    @Test
    void insertHistoricalTransitionals_exactTimeout_notInserted() {
        // Gap of exactly candidateTimeoutMinutes * 60 seconds — must NOT insert transitional.
        LocalDateTime serviceOut = T0.plusMinutes(5);
        LocalDateTime driveInIn = serviceOut.plusSeconds(5 * 60); // gap = exactly 300 s

        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 2, 180, serviceOut),
                makeDetection("ABC123", 1, 0, driveInIn)
        );
        engine.processDetections(detections);
        engine.insertHistoricalTransitionals();

        List<Stage> nonCandidates = engine.getAllSequences().get(0).getStages().stream()
                .filter(s -> !s.isCandidate()).toList();

        boolean hasBackyard = nonCandidates.stream()
                .anyMatch(s -> s.getName().equals("backyard") && "transitional".equals(s.getType()));
        assertFalse(hasBackyard, "Gap of exactly 5 min must NOT produce a historical backyard transitional");
    }

    // ========== BUGFIX-31-03-2026 TEST CASES ==========

    @Test
    void testParkingMultipleOutInCycles() {
        // Task 1: OUT→IN cycles must produce separate stages, not be deduped
        // drive_in IN → OUT → IN → OUT: 2 separate drive_in stages expected
        LocalDateTime t1 = T0.plusMinutes(10);
        LocalDateTime t2 = T0.plusMinutes(20);
        LocalDateTime t3 = T0.plusMinutes(30);

        engine.processDetections(List.of(
                makeDetection("ABC123", 1, 0, T0),      // drive_in IN  — stage 1 open
                makeDetection("ABC123", 1, 180, t1),    // drive_in OUT — stage 1 outTime
                makeDetection("ABC123", 1, 0, t2),      // drive_in IN  — must open stage 2 (not deduped)
                makeDetection("ABC123", 1, 180, t3)     // drive_in OUT — stage 2 outTime
        ));

        PlateSequence seq = engine.getAllSequences().get(0);
        List<Stage> driveIns = seq.getStages().stream()
                .filter(s -> s.getName().equals("drive_in") && !s.isCandidate()).toList();

        assertEquals(2, driveIns.size(), "Two OUT→IN cycles must produce 2 separate drive_in stages");
        assertEquals(T0, driveIns.get(0).getInTime());
        assertEquals(t1, driveIns.get(0).getOutTime());
        assertEquals(t2, driveIns.get(1).getInTime());
        assertEquals(t3, driveIns.get(1).getOutTime());
    }

    @Test
    void testAutoStartAfterDifferentStageOut() {
        // Task 2: checkTransitionalAutoStart must be called in the «different stage» OUT branch
        // service IN → drive_in OUT (closes service via different-stage path) → backyard candidate created
        LocalDateTime t1 = T0.plusMinutes(10);

        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),      // service IN
                makeDetection("ABC123", 1, 180, t1)     // drive_in OUT — different stage, closes service
        ));

        // Candidate must exist before materialization
        PlateSequence seq = engine.getAllSequences().get(0);
        assertEquals(1, seq.getCandidates().size(),
                "Backyard candidate must be created when service is closed by a different-stage OUT");

        // Materialize by advancing past candidateTimeoutMinutes (5 min = 300s)
        engine.performMaintenance(302);

        Stage backyard = engine.getAllSequences().get(0).getStages().stream()
                .filter(s -> s.getName().equals("backyard") && !s.isCandidate())
                .findFirst().orElse(null);
        assertNotNull(backyard, "Backyard must be materialized after candidate timeout");
        assertTrue(backyard.isFull());
    }

    @Test
    void testSequenceClosesOnGap() {
        // Task 3b: gap > sequenceCloseTimeoutMinutes must close the sequence and open a new one
        int timeoutMinutes = 2880;
        setSequenceCloseTimeout(timeoutMinutes);

        LocalDateTime t1 = T0.plusMinutes(timeoutMinutes + 1);

        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0),           // service IN — first sequence
                makeDetection("ABC123", 2, 0, t1)            // service IN — 2881 min later
        ));

        List<PlateSequence> seqs = engine.getAllSequences();
        assertEquals(2, seqs.size(), "Gap of 2881 min > 2880 min timeout must produce two sequences");
        assertEquals(1, seqs.stream().filter(s -> !s.isActive()).count(), "First sequence must be closed");
        assertEquals(1, seqs.stream().filter(PlateSequence::isActive).count(), "Second sequence must be active");
    }

    @Test
    void testBackyardOverrideTimeoutCloses() {
        // Task 3a: pending candidate with override timeout must close the sequence on next detection
        // backyard.sequenceCloseTimeoutOverrideMinutes = 2880
        int overrideMinutes = 2880;
        setBackyardOverrideTimeout(overrideMinutes);

        LocalDateTime serviceOut = T0;
        // candidate inTime = serviceOut + 1s; detection at +2881min → 2880min59s >= 2880min → closes
        LocalDateTime newDetection = T0.plusMinutes(overrideMinutes + 1);

        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, T0.minusMinutes(5)), // service IN
                makeDetection("ABC123", 2, 180, serviceOut)         // service OUT → backyard candidate
        ));

        // Candidate must exist (not yet materialized — no performMaintenance)
        assertEquals(1, engine.getAllSequences().get(0).getCandidates().size(),
                "Backyard candidate must exist after service OUT");

        // New detection after override timeout — must close old sequence and start new
        engine.processDetections(List.of(
                makeDetection("ABC123", 2, 0, newDetection)
        ));

        List<PlateSequence> seqs = engine.getAllSequences();
        assertEquals(2, seqs.size(), "Sequence must close when candidate override timeout is exceeded");

        // Closed sequence must not contain a materialized backyard
        PlateSequence closed = seqs.stream().filter(s -> !s.isActive()).findFirst().orElseThrow();
        boolean hasBackyard = closed.getStages().stream()
                .anyMatch(s -> s.getName().equals("backyard") && !s.isCandidate());
        assertFalse(hasBackyard, "Backyard must not be inserted into a sequence closed by override timeout");
    }

    @Test
    void testPartialStageAlwaysFullFalse() {
        // Task 4: a partial stage (created by an OUT with no prior IN for that stage) must have full=false
        // drive_in IN → service OUT (no service IN) → service partial stage created
        LocalDateTime t1 = T0.plusMinutes(10);

        engine.processDetections(List.of(
                makeDetection("ABC123", 1, 0, T0),      // drive_in IN
                makeDetection("ABC123", 2, 180, t1)     // service OUT — creates partial (no IN for service)
        ));

        PlateSequence seq = engine.getAllSequences().get(0);
        Stage partial = seq.getStages().stream()
                .filter(s -> s.getName().equals("service") && !s.isCandidate())
                .findFirst().orElseThrow(() -> new AssertionError("service partial stage not found"));

        assertFalse(partial.isFull(), "Partial stage must have full=false");
        assertNull(partial.getInTime(), "Partial stage must have inTime=null");
        assertNotNull(partial.getOutTime(), "Partial stage must have outTime set");
        assertEquals(t1, partial.getOutTime());
    }

    // Helper to override sequenceCloseTimeoutMinutes in the test config
    private void setSequenceCloseTimeout(int minutes) {
        try {
            var configField = ConfigLoader.class.getDeclaredField("config");
            configField.setAccessible(true);
            AppConfig config = (AppConfig) configField.get(configLoader);
            config.getWorkflow().setSequenceCloseTimeoutMinutes(minutes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setBackyardOverrideTimeout(int minutes) {
        try {
            var configField = ConfigLoader.class.getDeclaredField("config");
            configField.setAccessible(true);
            AppConfig config = (AppConfig) configField.get(configLoader);
            config.getWorkflow().getTransitional().stream()
                    .filter(tc -> tc.getName().equals("backyard"))
                    .findFirst().orElseThrow()
                    .setSequenceCloseTimeoutOverrideMinutes(minutes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void insertHistoricalTransitionals_addsTransitionalBetweenStages() {
        // service OUT at T0+5, then drive_in IN at T0+15 (10 min gap > 5 min candidate timeout)
        LocalDateTime t1 = T0.plusMinutes(5);
        LocalDateTime t2 = T0.plusMinutes(15);
 
        List<Detection> detections = List.of(
                makeDetection("ABC123", 2, 0, T0),
                makeDetection("ABC123", 2, 180, t1),
                makeDetection("ABC123", 1, 0, t2)
        );
        engine.processDetections(detections);
        engine.insertHistoricalTransitionals();
 
        PlateSequence seq = engine.getAllSequences().get(0);
        List<Stage> nonCandidates = seq.getStages().stream()
                .filter(s -> !s.isCandidate()).toList();
 
        // Should have: service, backyard (transitional), drive_in
        boolean hasBackyard = nonCandidates.stream()
                .anyMatch(s -> s.getName().equals("backyard") && "transitional".equals(s.getType()));
        assertTrue(hasBackyard, "Historical transitional 'backyard' should be inserted");
    }
}