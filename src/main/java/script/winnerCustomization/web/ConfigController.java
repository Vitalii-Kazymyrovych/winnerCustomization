package script.winnerCustomization.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.model.AppConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class ConfigController {
    private final RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper;

    public ConfigController(RuntimeConfig runtimeConfig, ObjectMapper objectMapper) {
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public AppConfig getConfig() {
        return runtimeConfig.get();
    }

    @GetMapping(value = "/config", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getConfigHtml() throws JsonProcessingException {
        String json = escapeHtml(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runtimeConfig.get()));
        return htmlResponse("""
                <!doctype html>
                <html lang=\"uk\"><head><meta charset=\"utf-8\"><title>Config</title></head>
                <body>
                  <h1>Runtime configuration</h1>
                  <p><a href=\"/config/help\">Specification help</a></p>
                  <form method=\"post\" action=\"/config\">
                    <textarea name=\"json\" style=\"width:100%%;min-height:32rem\">%s</textarea>
                    <br><button type=\"submit\">Save</button>
                  </form>
                </body></html>
                """.formatted(json));
    }

    @GetMapping(value = "/config/help", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getConfigHelpHtml() {
        return htmlResponse("""
                <!doctype html>
                <html lang=\"uk\"><head><meta charset=\"utf-8\"><title>Config help</title></head>
                <body>
                  <h1>Нова конфігурація sequence engine</h1>
                  <ul>
                    <li><code>sequenceCloseTimeoutMinutes</code> — глобальний тайм-аут закриття послідовності.</li>
                    <li><code>notifications[]</code> — правила оповіщень по камерах.</li>
                    <li><code>realStages[]</code> — етапи з явними <em>In</em>/<em>Out</em>.</li>
                    <li><code>transitionalStages[]</code> — кандидати, що materialize лише після тайм-ауту.</li>
                    <li><code>singleCameraStages[]</code> — sticky-етапи для постів.</li>
                  </ul>
                </body></html>
                """);
    }

    @PostMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AppConfig updateConfig(@RequestBody AppConfig config) throws IOException {
        return runtimeConfig.save(config);
    }

    @PostMapping(value = "/config", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> updateConfigForm(@RequestParam("json") String json,
                                                   @RequestHeader(value = "Accept", required = false) String acceptHeader) throws IOException {
        AppConfig saved = runtimeConfig.save(objectMapper.readValue(json, AppConfig.class));
        if (acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(objectMapper.writeValueAsString(saved));
        }
        return htmlResponse("<html><body><p>Saved.</p><p><a href=\"/config\">Back</a></p></body></html>");
    }

    private ResponseEntity<String> htmlResponse(String html) {
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(html);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
