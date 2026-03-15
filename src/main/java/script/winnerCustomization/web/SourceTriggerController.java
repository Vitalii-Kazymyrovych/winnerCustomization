package script.winnerCustomization.web;

import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.service.SourcePullTriggerService;

import java.util.Map;

@RestController
public class SourceTriggerController {
    private static final Logger log = LoggerFactory.getLogger(SourceTriggerController.class);

    private final SourcePullTriggerService sourcePullTriggerService;

    public SourceTriggerController(SourcePullTriggerService sourcePullTriggerService) {
        this.sourcePullTriggerService = sourcePullTriggerService;
    }

    @GetMapping("/source/trigger-pull")
    public ResponseEntity<Map<String, Object>> triggerSourcePull() {
        log.info("HTTP GET /source/trigger-pull received");
        SourcePullTriggerService.TriggerResult result = sourcePullTriggerService.triggerPull();
        log.info("Trigger result status={}", result.status());

        if (result.status() == SourcePullTriggerService.Status.TRIGGERED) {
            return ResponseEntity.ok(Map.of(
                    "status", result.status().name(),
                    "detectionsLoaded", result.detectionsLoaded()
            ));
        }

        if (result.status() == SourcePullTriggerService.Status.COOLDOWN) {
            return ResponseEntity.status(429).body(Map.of(
                    "status", result.status().name(),
                    "retryAfterMillis", result.retryAfterMillis()
            ));
        }

        return ResponseEntity.status(409).body(Map.of(
                "status", result.status().name(),
                "message", "Trigger is already running"
        ));
    }
}
