package script.winnerCustomization.notifications;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.config.TestConfigFactory;
import script.winnerCustomization.model.Detection;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPlannerTest {
    private final NotificationPlanner planner = new NotificationPlanner();

    @Test
    void dueNotificationSurvivesSameCameraDetectionsAndCancelsOnOtherCamera() {
        var config = TestConfigFactory.standardConfig();
        LocalDateTime base = LocalDateTime.of(2026, 3, 23, 10, 0);

        var due = planner.collectDueNotifications(List.of(
                new Detection(1, "AA1111", 1001, 90, base),
                new Detection(2, "AA1111", 1001, 90, base.plusMinutes(1)),
                new Detection(3, "BB2222", 1001, 90, base),
                new Detection(4, "BB2222", 1003, null, base.plusMinutes(10))
        ), config, base.plusMinutes(20));

        assertThat(due).singleElement().satisfies(notification -> {
            assertThat(notification.plateNumber()).isEqualTo("AA1111");
            assertThat(notification.message()).contains("No Drive in (out) within 15 minutes");
        });
    }
}
