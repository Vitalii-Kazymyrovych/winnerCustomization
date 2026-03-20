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
    public String getConfigHtml() throws JsonProcessingException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runtimeConfig.get())
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return """
                <!doctype html>
                <html lang=\"en\">
                <head>
                  <meta charset=\"utf-8\" />
                  <title>winnerCustomization config</title>
                  <style>
                    body { font-family: sans-serif; margin: 2rem; }
                    textarea { width: 100%%; min-height: 36rem; font-family: monospace; }
                    button { margin-top: 1rem; padding: 0.75rem 1rem; }
                  </style>
                </head>
                <body>
                  <h1>Runtime configuration</h1>
                  <form method=\"post\" action=\"/config\">
                    <textarea name=\"json\">%s</textarea>
                    <br />
                    <button type=\"submit\">Save configuration</button>
                  </form>
                </body>
                </html>
                """.formatted(json);
    }

    @PostMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AppConfig saveJson(@RequestBody AppConfig config) throws IOException {
        return runtimeConfig.save(config);
    }

    @PostMapping(value = "/config", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> saveForm(@RequestParam("json") String json,
                                           @RequestHeader(value = "Accept", required = false) String acceptHeader) throws IOException {
        AppConfig config = objectMapper.readValue(json, AppConfig.class);
        runtimeConfig.save(config);
        if (acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runtimeConfig.get()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(getConfigHtml());
    }
}
