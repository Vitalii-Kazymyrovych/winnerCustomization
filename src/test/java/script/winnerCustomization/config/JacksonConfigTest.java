package script.winnerCustomization.config;

import org.junit.jupiter.api.Test;
import script.winnerCustomization.model.AppConfig;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    @Test
    void shouldDeserializeLocalDateTimeForSourceTableLoadFrom() throws Exception {
        JacksonConfig jacksonConfig = new JacksonConfig();
        String json = """
                {
                  \"sourceTable\": {
                    \"table\": \"alpr_detections\",
                    \"loadFrom\": \"2026-03-17T09:30:00\"
                  }
                }
                """;

        AppConfig config = jacksonConfig.objectMapper().readValue(json, AppConfig.class);

        assertThat(config.getSourceTable().getLoadFrom())
                .isEqualTo(LocalDateTime.of(2026, 3, 17, 9, 30, 0));
    }
}
