package script.winnerCustomization.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class ConfigController {
    private final RuntimeConfig runtimeConfig;
    private final ObjectMapper objectMapper;
    private final Path technicalTaskPdfPath;

    public ConfigController(RuntimeConfig runtimeConfig, ObjectMapper objectMapper) {
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        this.technicalTaskPdfPath = Path.of(System.getProperty("user.dir"), "task.pdf");
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public AppConfig getConfig() {
        return runtimeConfig.get();
    }

    @GetMapping(value = "/config", produces = MediaType.TEXT_HTML_VALUE)
    public String getConfigHtml() throws JsonProcessingException {
        return renderConfigHtml(runtimeConfig.get());
    }

    @GetMapping(value = "/config/help", produces = MediaType.TEXT_HTML_VALUE)
    public String getHelpHtml() {
        return """
                <!doctype html>
                <html lang=\"en\">
                <head>
                  <meta charset=\"utf-8\" />
                  <title>winnerCustomization workflow help</title>
                  <style>
                    body { font-family: Arial, sans-serif; margin: 2rem auto; max-width: 1100px; line-height: 1.6; color: #1f2937; background: #f8fafc; padding: 0 1rem 3rem; }
                    h1, h2, h3 { color: #111827; }
                    section, .note, .example { background: #ffffff; border: 1px solid #dbe4f0; border-radius: 12px; padding: 1rem 1.25rem; margin-bottom: 1rem; box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06); }
                    code { background: #eff6ff; padding: 0.15rem 0.35rem; border-radius: 6px; }
                    ul, ol { padding-left: 1.4rem; }
                    .good { border-left: 4px solid #16a34a; }
                    .bad { border-left: 4px solid #dc2626; }
                    a { color: #2563eb; }
                  </style>
                </head>
                <body>
                  <h1>Workflow configuration help</h1>
                  <p>This page explains how to fill the simplified workflow editor available at <a href=\"/config\">/config</a>.</p>

                  <section>
                    <h2>Main entities</h2>
                    <ul>
                      <li><strong>Stage</strong> describes one business step in the vehicle flow such as <code>drive_in</code>, <code>service</code>, or <code>parking</code>.</li>
                      <li><strong>Trigger</strong> describes an event that starts or finishes a stage. A trigger usually matches camera, direction range, and event type.</li>
                      <li><strong>directionRange</strong> narrows a trigger to a direction interval. Empty values mean that direction is not checked.</li>
                      <li><strong>labelTemplate</strong> is the text shown in reports. Use placeholders only if your workflow logic expects them, for example post-specific labels.</li>
                    </ul>
                  </section>

                  <section>
                    <h2>Required stages</h2>
                    <p>For the expected dealership flow, these stages should exist in the workflow:</p>
                    <ul>
                      <li><code>drive_in</code> &mdash; vehicle entered the site.</li>
                      <li><code>service</code> &mdash; vehicle is at a service work area.</li>
                      <li><code>parking</code> &mdash; vehicle is parked after service or between actions.</li>
                    </ul>
                    <p>A <strong>transitional</strong> stage is an auxiliary stage that describes movement between major stages. It normally should not behave like a long-lived business stage. Example: a backyard/test-drive transition stage.</p>
                  </section>

                  <section>
                    <h2>Stage fields</h2>
                    <ul>
                      <li><code>startMode</code>: <code>immediate</code> starts the stage right away; <code>candidate</code> waits for confirmation/timeout logic.</li>
                      <li><code>finishMode</code>: <code>immediate</code> closes on the first finish trigger; <code>sticky</code> keeps the stage open until another stage or timeout closes it.</li>
                      <li><code>startDuplicatePolicy</code>: how repeated start events behave (<code>ignore</code>, <code>restart</code>, <code>refresh_candidate</code>).</li>
                      <li><code>finishDuplicatePolicy</code>: how repeated finish events behave (<code>ignore</code>, <code>update_sticky</code>).</li>
                      <li><code>allowedNextStages</code>: list of stages that may follow the current stage without being treated as unexpected.</li>
                      <li><code>unexpectedNextStagePolicy</code>: action for a transition outside <code>allowedNextStages</code>.</li>
                      <li><code>candidateTimeoutMinutes</code>, <code>candidateCloseTimeoutMinutes</code>, <code>stickyCloseTimeoutMinutes</code>, <code>sequenceCloseTimeoutMinutes</code>, <code>sameStageReopenAfterMinutes</code>: timeout controls in minutes.</li>
                      <li><code>timeoutTransitionToStage</code>: optional stage name to start automatically when timeout logic closes the current stage.</li>
                      <li><code>intermediateStageOnTransition</code>: optional helper stage inserted before the next stage for special transitions.</li>
                      <li><code>saveStageAfterSequenceClosed</code>: keep this stage in reports after sequence finalization.</li>
                      <li><code>allowPartialFromFinish</code>: allows recovery rows created only from finish events.</li>
                    </ul>
                  </section>

                  <section>
                    <h2>Trigger fields</h2>
                    <ul>
                      <li><code>cameraId</code> identifies the analytics camera.</li>
                      <li><code>eventType</code> is usually <code>in</code>, <code>out</code>, <code>candidate</code>, or <code>custom</code>.</li>
                      <li><code>eventKey</code> must be stable and unique enough for your logic and notifications.</li>
                      <li><code>derivedStageInstance</code> is useful for repeated stage families such as multiple service posts.</li>
                      <li><code>notification.enabled</code>, <code>template</code>, and <code>delayMinutes</code> configure delayed alerts for the trigger.</li>
                    </ul>
                  </section>

                  <section class=\"example good\">
                    <h2>Examples of valid configuration ideas</h2>
                    <ul>
                      <li><code>drive_in</code> allows next stage <code>service</code> and finishes immediately on exit camera.</li>
                      <li><code>service</code> uses sticky finish mode when service-out is noisy and the next confirmed stage should close it.</li>
                      <li><code>parking</code> allows partial recovery from finish events if only parking-out is observed.</li>
                    </ul>
                  </section>

                  <section class=\"example bad\">
                    <h2>Examples of problematic settings</h2>
                    <ul>
                      <li>Missing <code>name</code> or <code>labelTemplate</code> for a stage.</li>
                      <li>Two stages with the same <code>name</code>.</li>
                      <li><code>allowedNextStages</code> references a stage that does not exist.</li>
                      <li>A trigger with empty <code>cameraId</code> or empty <code>eventKey</code>.</li>
                      <li><code>directionRange.from</code> equal to <code>directionRange.to</code>.</li>
                    </ul>
                  </section>

                  <section class=\"note\">
                    <h2>Validation</h2>
                    <p>The web form keeps the UI simple and does not replace server validation. Final validation still happens in <code>RuntimeConfig.validate()</code>. If saving fails, the UI will show the server error message.</p>
                  </section>

                  <section>
                    <h2>Full technical task</h2>
                    <p>For full requirements, open the PDF technical task: <a href=\"/config/task.pdf\">task.pdf</a>.</p>
                  </section>
                </body>
                </html>
                """;
    }

    @GetMapping(value = "/config/task.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<ByteArrayResource> getTechnicalTaskPdf() throws IOException {
        byte[] content = Files.readAllBytes(technicalTaskPdfPath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("task.pdf").build().toString())
                .contentLength(content.length)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new ByteArrayResource(content));
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
                .body(renderConfigHtml(runtimeConfig.get()));
    }

    private String renderConfigHtml(AppConfig config) throws JsonProcessingException {
        String initialConfigJson = objectMapper.writeValueAsString(config)
                .replace("</", "<\\/");
        return """
                <!doctype html>
                <html lang=\"en\">
                <head>
                  <meta charset=\"utf-8\" />
                  <title>winnerCustomization config</title>
                  <style>
                    :root {
                      color-scheme: light;
                      font-family: Arial, sans-serif;
                    }
                    body {
                      margin: 0;
                      background: #f3f4f6;
                      color: #111827;
                    }
                    .page {
                      max-width: 1280px;
                      margin: 0 auto;
                      padding: 24px;
                    }
                    .toolbar, .workflow-settings, .panel, .trigger-list, .stage-header, .trigger-header {
                      display: flex;
                      gap: 12px;
                      flex-wrap: wrap;
                      align-items: center;
                    }
                    .toolbar {
                      justify-content: space-between;
                      margin-bottom: 16px;
                    }
                    .button-row {
                      display: flex;
                      gap: 12px;
                      flex-wrap: wrap;
                    }
                    button, .link-button {
                      border: 1px solid #9ca3af;
                      border-radius: 6px;
                      padding: 10px 14px;
                      font-size: 14px;
                      cursor: pointer;
                      text-decoration: none;
                      background: #ffffff;
                      color: #111827;
                    }
                    .primary { background: #2563eb; border-color: #2563eb; color: white; }
                    .secondary { background: #ffffff; }
                    .danger { background: #ffffff; border-color: #dc2626; color: #b91c1c; }
                    .page-note, .panel, .stage-card, .trigger-card, .trigger-column {
                      background: white;
                      border: 1px solid #d1d5db;
                      border-radius: 6px;
                    }
                    .page-note, .panel {
                      padding: 16px;
                      margin-bottom: 16px;
                    }
                    .page-note {
                      display: flex;
                      justify-content: space-between;
                      gap: 16px;
                      align-items: center;
                    }
                    .panel h2, .stage-card h2, .trigger-column h3, .trigger-group h3 {
                      margin-top: 0;
                    }
                    .workflow-settings {
                      margin-top: 16px;
                    }
                    .field-grid {
                      display: grid;
                      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                      gap: 12px;
                    }
                    .field {
                      display: flex;
                      flex-direction: column;
                      gap: 6px;
                    }
                    .field.wide {
                      grid-column: 1 / -1;
                    }
                    label {
                      font-size: 13px;
                      font-weight: 600;
                    }
                    input, select, textarea {
                      width: 100%%;
                      box-sizing: border-box;
                      border: 1px solid #9ca3af;
                      border-radius: 4px;
                      padding: 8px 10px;
                      font: inherit;
                      background: white;
                    }
                    textarea {
                      min-height: 88px;
                      resize: vertical;
                    }
                    .hint {
                      font-size: 12px;
                      color: #6b7280;
                    }
                    .status {
                      margin-bottom: 16px;
                      padding: 12px 14px;
                      border-radius: 6px;
                      display: none;
                      white-space: pre-wrap;
                    }
                    .status.success { display: block; background: #dcfce7; color: #166534; }
                    .status.error { display: block; background: #fee2e2; color: #991b1b; }
                    .stage-list {
                      display: flex;
                      flex-direction: column;
                      gap: 16px;
                    }
                    .stage-card {
                      padding: 16px;
                    }
                    .stage-header {
                      justify-content: space-between;
                      margin-bottom: 16px;
                    }
                    .stage-title {
                      font-size: 18px;
                      font-weight: 700;
                    }
                    .triggers-layout {
                      display: grid;
                      grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
                      gap: 16px;
                      margin-top: 16px;
                    }
                    .trigger-column {
                      padding: 12px;
                    }
                    .trigger-list {
                      flex-direction: column;
                      align-items: stretch;
                      margin-top: 12px;
                    }
                    .trigger-card {
                      padding: 12px;
                    }
                    .trigger-header {
                      justify-content: space-between;
                      margin-bottom: 12px;
                    }
                    .muted {
                      color: #6b7280;
                    }
                    code {
                      background: #f3f4f6;
                      border-radius: 4px;
                      padding: 2px 5px;
                    }
                    .summary-row {
                      display: flex;
                      flex-wrap: wrap;
                      gap: 12px;
                      margin-bottom: 12px;
                    }
                    .summary-item {
                      padding: 8px 10px;
                      border: 1px solid #d1d5db;
                      border-radius: 4px;
                      background: #ffffff;
                    }
                    @media (max-width: 720px) {
                      .page { padding: 12px; }
                      .page-note { align-items: flex-start; flex-direction: column; }
                    }
                  </style>
                </head>
                <body>
                  <div class=\"page\">
                    <div class=\"toolbar\">
                      <div>
                        <h1>Runtime workflow configuration</h1>
                        <p class=\"muted\">All existing stages are loaded immediately. Edit them directly, add new ones if needed, and keep the interface simple and working.</p>
                      </div>
                      <div class=\"button-row\">
                        <button id=\"new-stage-button\" type=\"button\" class=\"secondary\">New Stage</button>
                        <button id=\"save-button\" type=\"button\" class=\"primary\">Save configuration</button>
                        <a class=\"link-button secondary\" href=\"/config/help\">Instruction</a>
                      </div>
                    </div>

                    <div class=\"page-note\">
                      <div>
                        <strong>Workflow editor</strong>
                        <div class=\"muted\">This page edits <code>workflow</code>. Server-side validation still runs before the file is saved.</div>
                      </div>
                      <div class=\"muted\">Config file: <code>%s</code></div>
                    </div>

                    <div id=\"status\" class=\"status\"></div>

                    <section class=\"panel\">
                      <h2>Workflow settings</h2>
                      <div class=\"workflow-settings field-grid\">
                        <div class=\"field\">
                          <label for=\"default-sequence-close-timeout\">defaultSequenceCloseTimeoutMinutes</label>
                          <input id=\"default-sequence-close-timeout\" type=\"number\" min=\"1\" step=\"1\" />
                        </div>
                      </div>
                    </section>

                    <div id=\"stage-summary\" class=\"summary-row\"></div>
                    <div id=\"stage-list\" class=\"stage-list\"></div>
                  </div>

                  <script id=\"initial-config\" type=\"application/json\">%s</script>
                  <script>
                    const START_MODE_OPTIONS = ["immediate", "candidate"];
                    const FINISH_MODE_OPTIONS = ["immediate", "sticky"];
                    const UNEXPECTED_NEXT_STAGE_OPTIONS = ["close_current_and_start_next", "insert_intermediate_and_start_next", "ignore", "start_partial_next"];
                    const START_DUPLICATE_POLICY_OPTIONS = ["ignore", "restart", "refresh_candidate"];
                    const FINISH_DUPLICATE_POLICY_OPTIONS = ["ignore", "update_sticky"];
                    const EVENT_TYPE_OPTIONS = ["in", "out", "candidate", "custom"];
                    const BOOLEAN_OPTIONS = ["true", "false"];

                    const initialConfig = JSON.parse(document.getElementById("initial-config").textContent || "{}");
                    const stageList = document.getElementById("stage-list");
                    const stageSummary = document.getElementById("stage-summary");
                    const statusBox = document.getElementById("status");
                    const defaultSequenceCloseTimeoutInput = document.getElementById("default-sequence-close-timeout");

                    const state = {
                      config: normalizeConfig(initialConfig)
                    };

                    document.getElementById("new-stage-button").addEventListener("click", () => {
                      state.config.workflow.stages.push(createEmptyStage());
                      render();
                    });

                    document.getElementById("save-button").addEventListener("click", saveConfiguration);

                    render();

                    function normalizeConfig(config) {
                      const normalized = cloneValue(config || {});
                      if (!normalized.workflow) {
                        normalized.workflow = {};
                      }
                      if (!Array.isArray(normalized.workflow.stages)) {
                        normalized.workflow.stages = [];
                      }
                      if (!normalized.workflow.defaultSequenceCloseTimeoutMinutes) {
                        normalized.workflow.defaultSequenceCloseTimeoutMinutes = 2880;
                      }
                      normalized.workflow.stages = normalized.workflow.stages.map(normalizeStage);
                      return normalized;
                    }

                    function normalizeStage(stage) {
                      const normalized = cloneValue(stage || {});
                      normalized.name = normalized.name || "";
                      normalized.labelTemplate = normalized.labelTemplate || "";
                      normalized.startMode = normalized.startMode || "immediate";
                      normalized.finishMode = normalized.finishMode || "immediate";
                      normalized.startDuplicatePolicy = normalized.startDuplicatePolicy || "ignore";
                      normalized.finishDuplicatePolicy = normalized.finishDuplicatePolicy || "update_sticky";
                      normalized.unexpectedNextStagePolicy = normalized.unexpectedNextStagePolicy || "close_current_and_start_next";
                      normalized.startTriggers = Array.isArray(normalized.startTriggers) ? normalized.startTriggers.map(normalizeTrigger) : [];
                      normalized.finishTriggers = Array.isArray(normalized.finishTriggers) ? normalized.finishTriggers.map(normalizeTrigger) : [];
                      normalized.allowedNextStages = Array.isArray(normalized.allowedNextStages) ? normalized.allowedNextStages : [];
                      normalized.candidateCancelOnEvents = Array.isArray(normalized.candidateCancelOnEvents) ? normalized.candidateCancelOnEvents : [];
                      normalized.saveStageAfterSequenceClosed = normalized.saveStageAfterSequenceClosed !== false;
                      normalized.allowPartialFromFinish = Boolean(normalized.allowPartialFromFinish);
                      normalized.transitional = Boolean(normalized.transitional);
                      return normalized;
                    }

                    function normalizeTrigger(trigger) {
                      const normalized = cloneValue(trigger || {});
                      normalized.cameraId = normalized.cameraId ?? "";
                      normalized.eventType = normalized.eventType || "in";
                      normalized.eventKey = normalized.eventKey || "";
                      normalized.derivedStageInstance = normalized.derivedStageInstance || "";
                      normalized.name = normalized.name || "";
                      normalized.directionRange = normalized.directionRange || {};
                      normalized.notification = normalized.notification || { enabled: false, template: "", delayMinutes: "" };
                      if (typeof normalized.notification.enabled !== "boolean") {
                        normalized.notification.enabled = String(normalized.notification.enabled) === "true";
                      }
                      return normalized;
                    }

                    function createEmptyStage() {
                      return normalizeStage({
                        name: "",
                        labelTemplate: "",
                        startMode: "immediate",
                        finishMode: "immediate",
                        startDuplicatePolicy: "ignore",
                        finishDuplicatePolicy: "update_sticky",
                        unexpectedNextStagePolicy: "close_current_and_start_next",
                        startTriggers: [],
                        finishTriggers: [],
                        allowedNextStages: [],
                        candidateCancelOnEvents: [],
                        saveStageAfterSequenceClosed: true,
                        allowPartialFromFinish: false,
                        transitional: false
                      });
                    }

                    function createEmptyTrigger(eventType) {
                      return normalizeTrigger({
                        cameraId: "",
                        directionRange: {},
                        eventType: eventType,
                        eventKey: "",
                        derivedStageInstance: "",
                        name: "",
                        notification: { enabled: false, template: "", delayMinutes: "" }
                      });
                    }

                    function cloneValue(value) {
                      if (typeof structuredClone === "function") {
                        return structuredClone(value);
                      }
                      return JSON.parse(JSON.stringify(value));
                    }

                    function renderSummary() {
                      const stages = state.config.workflow.stages;
                      const triggerCount = stages.reduce((sum, stage) => sum + stage.startTriggers.length + stage.finishTriggers.length, 0);
                      const stageNames = stages.map(stage => stage.name).filter(Boolean);
                      stageSummary.innerHTML = "";
                      [
                        `Stages: ${stages.length}`,
                        `Triggers: ${triggerCount}`,
                        stageNames.length > 0 ? `Loaded: ${stageNames.join(", ")}` : "Loaded: none"
                      ].forEach(text => {
                        const item = document.createElement("div");
                        item.className = "summary-item";
                        item.textContent = text;
                        stageSummary.appendChild(item);
                      });
                    }

                    function render() {
                      defaultSequenceCloseTimeoutInput.value = state.config.workflow.defaultSequenceCloseTimeoutMinutes ?? "";
                      renderSummary();
                      stageList.innerHTML = "";
                      if (state.config.workflow.stages.length === 0) {
                        const empty = document.createElement("section");
                        empty.className = "panel";
                        empty.innerHTML = "<strong>No stages yet.</strong><div class=\"muted\">Use <code>New Stage</code> to create the first workflow stage.</div>";
                        stageList.appendChild(empty);
                        return;
                      }
                      state.config.workflow.stages.forEach((stage, index) => {
                        stageList.appendChild(renderStage(stage, index));
                      });
                    }

                    function renderStage(stage, stageIndex) {
                      const card = document.createElement("section");
                      card.className = "stage-card";
                      const stageName = stage.name || `Stage ${stageIndex + 1}`;
                      card.innerHTML = `
                        <div class="stage-header">
                          <div>
                            <div class="stage-title">${escapeHtml(stageName)}</div>
                            <div class="muted">Stage #${stageIndex + 1}</div>
                          </div>
                          <button type="button" class="danger">Delete stage</button>
                        </div>
                        <div class="field-grid"></div>
                        <div class="triggers-layout"></div>
                      `;
                      card.querySelector(".danger").addEventListener("click", () => {
                        state.config.workflow.stages.splice(stageIndex, 1);
                        render();
                      });
                      const fieldGrid = card.querySelector(".field-grid");
                      appendField(fieldGrid, textField("name", stage.name, value => stage.name = value, true));
                      appendField(fieldGrid, textField("labelTemplate", stage.labelTemplate, value => stage.labelTemplate = value, true));
                      appendField(fieldGrid, selectField("startMode", START_MODE_OPTIONS, stage.startMode, value => stage.startMode = value));
                      appendField(fieldGrid, numericField("candidateTimeoutMinutes", stage.candidateTimeoutMinutes, value => stage.candidateTimeoutMinutes = value));
                      appendField(fieldGrid, numericField("candidateCloseTimeoutMinutes", stage.candidateCloseTimeoutMinutes, value => stage.candidateCloseTimeoutMinutes = value));
                      appendField(fieldGrid, textareaField("candidateCancelOnEvents", toMultiline(stage.candidateCancelOnEvents), value => stage.candidateCancelOnEvents = parseList(value), "One event key per line or comma-separated."));
                      appendField(fieldGrid, selectField("finishMode", FINISH_MODE_OPTIONS, stage.finishMode, value => stage.finishMode = value));
                      appendField(fieldGrid, numericField("stickyCloseTimeoutMinutes", stage.stickyCloseTimeoutMinutes, value => stage.stickyCloseTimeoutMinutes = value));
                      appendField(fieldGrid, textareaField("allowedNextStages", toMultiline(stage.allowedNextStages), value => stage.allowedNextStages = parseList(value), "Reference stage names, one per line or comma-separated."));
                      appendField(fieldGrid, selectField("unexpectedNextStagePolicy", UNEXPECTED_NEXT_STAGE_OPTIONS, stage.unexpectedNextStagePolicy, value => stage.unexpectedNextStagePolicy = value));
                      appendField(fieldGrid, textField("timeoutTransitionToStage", stage.timeoutTransitionToStage, value => stage.timeoutTransitionToStage = emptyToNull(value)));
                      appendField(fieldGrid, numericField("sequenceCloseTimeoutMinutes", stage.sequenceCloseTimeoutMinutes, value => stage.sequenceCloseTimeoutMinutes = value));
                      appendField(fieldGrid, selectField("saveStageAfterSequenceClosed", BOOLEAN_OPTIONS, String(stage.saveStageAfterSequenceClosed !== false), value => stage.saveStageAfterSequenceClosed = value === "true"));
                      appendField(fieldGrid, selectField("allowPartialFromFinish", BOOLEAN_OPTIONS, String(Boolean(stage.allowPartialFromFinish)), value => stage.allowPartialFromFinish = value === "true"));
                      appendField(fieldGrid, selectField("startDuplicatePolicy", START_DUPLICATE_POLICY_OPTIONS, stage.startDuplicatePolicy, value => stage.startDuplicatePolicy = value));
                      appendField(fieldGrid, selectField("finishDuplicatePolicy", FINISH_DUPLICATE_POLICY_OPTIONS, stage.finishDuplicatePolicy, value => stage.finishDuplicatePolicy = value));
                      appendField(fieldGrid, textField("intermediateStageOnTransition", stage.intermediateStageOnTransition, value => stage.intermediateStageOnTransition = emptyToNull(value)));
                      appendField(fieldGrid, selectField("transitional", BOOLEAN_OPTIONS, String(Boolean(stage.transitional)), value => stage.transitional = value === "true"));
                      appendField(fieldGrid, numericField("sameStageReopenAfterMinutes", stage.sameStageReopenAfterMinutes, value => stage.sameStageReopenAfterMinutes = value));

                      const triggersLayout = card.querySelector(".triggers-layout");
                      triggersLayout.appendChild(renderTriggerColumn(stage, stageIndex, "startTriggers", "Start triggers", "in"));
                      triggersLayout.appendChild(renderTriggerColumn(stage, stageIndex, "finishTriggers", "Finish triggers", "out"));
                      return card;
                    }

                    function renderTriggerColumn(stage, stageIndex, fieldName, title, defaultEventType) {
                      const column = document.createElement("div");
                      column.className = "trigger-column";
                      column.innerHTML = `
                        <div class="trigger-header">
                          <div>
                            <h3>${escapeHtml(title)}</h3>
                            <div class="muted">${fieldName}</div>
                          </div>
                          <button type="button" class="secondary">New Trigger</button>
                        </div>
                        <div class="trigger-list"></div>
                      `;
                      column.querySelector("button").addEventListener("click", () => {
                        stage[fieldName].push(createEmptyTrigger(defaultEventType));
                        render();
                      });
                      const triggerList = column.querySelector(".trigger-list");
                      if (stage[fieldName].length === 0) {
                        const empty = document.createElement("div");
                        empty.className = "muted";
                        empty.textContent = "No triggers configured.";
                        triggerList.appendChild(empty);
                      } else {
                        stage[fieldName].forEach((trigger, triggerIndex) => {
                          triggerList.appendChild(renderTrigger(stage, fieldName, trigger, stageIndex, triggerIndex));
                        });
                      }
                      return column;
                    }

                    function renderTrigger(stage, fieldName, trigger, stageIndex, triggerIndex) {
                      const card = document.createElement("div");
                      card.className = "trigger-card";
                      card.innerHTML = `
                        <div class="trigger-header">
                          <strong>Trigger #${triggerIndex + 1}</strong>
                          <button type="button" class="danger">Delete trigger</button>
                        </div>
                        <div class="field-grid"></div>
                      `;
                      card.querySelector(".danger").addEventListener("click", () => {
                        stage[fieldName].splice(triggerIndex, 1);
                        render();
                      });
                      const fieldGrid = card.querySelector(".field-grid");
                      appendField(fieldGrid, textField("name", trigger.name, value => trigger.name = value));
                      appendField(fieldGrid, numericField("cameraId", trigger.cameraId, value => trigger.cameraId = value));
                      appendField(fieldGrid, numericField("directionRange.from", trigger.directionRange?.from, value => setNestedDirection(trigger, "from", value)));
                      appendField(fieldGrid, numericField("directionRange.to", trigger.directionRange?.to, value => setNestedDirection(trigger, "to", value)));
                      appendField(fieldGrid, selectField("eventType", EVENT_TYPE_OPTIONS, trigger.eventType || "in", value => trigger.eventType = value));
                      appendField(fieldGrid, textField("eventKey", trigger.eventKey, value => trigger.eventKey = value, true));
                      appendField(fieldGrid, textField("derivedStageInstance", trigger.derivedStageInstance, value => trigger.derivedStageInstance = value));
                      appendField(fieldGrid, selectField("notification.enabled", BOOLEAN_OPTIONS, String(Boolean(trigger.notification?.enabled)), value => ensureNotification(trigger).enabled = value === "true"));
                      appendField(fieldGrid, textField("notification.template", trigger.notification?.template, value => ensureNotification(trigger).template = value));
                      appendField(fieldGrid, numericField("notification.delayMinutes", trigger.notification?.delayMinutes, value => ensureNotification(trigger).delayMinutes = value));
                      return card;
                    }

                    function appendField(container, field) {
                      container.appendChild(field);
                    }

                    function textField(labelText, value, onChange, required = false) {
                      const wrapper = createFieldWrapper(labelText);
                      const input = document.createElement("input");
                      input.type = "text";
                      input.value = value ?? "";
                      input.required = required;
                      input.addEventListener("input", event => onChange(event.target.value));
                      wrapper.appendChild(input);
                      return wrapper;
                    }

                    function numericField(labelText, value, onChange) {
                      const wrapper = createFieldWrapper(labelText);
                      const input = document.createElement("input");
                      input.type = "number";
                      input.step = "1";
                      input.value = value ?? "";
                      input.addEventListener("input", event => onChange(parseInteger(event.target.value)));
                      wrapper.appendChild(input);
                      return wrapper;
                    }

                    function selectField(labelText, options, value, onChange) {
                      const wrapper = createFieldWrapper(labelText);
                      const select = document.createElement("select");
                      options.forEach(optionValue => {
                        const option = document.createElement("option");
                        option.value = optionValue;
                        option.textContent = optionValue;
                        if (optionValue === value) {
                          option.selected = true;
                        }
                        select.appendChild(option);
                      });
                      select.addEventListener("change", event => onChange(event.target.value));
                      wrapper.appendChild(select);
                      return wrapper;
                    }

                    function textareaField(labelText, value, onChange, hintText) {
                      const wrapper = createFieldWrapper(labelText, true);
                      const textarea = document.createElement("textarea");
                      textarea.value = value ?? "";
                      textarea.addEventListener("input", event => onChange(event.target.value));
                      wrapper.appendChild(textarea);
                      if (hintText) {
                        const hint = document.createElement("div");
                        hint.className = "hint";
                        hint.textContent = hintText;
                        wrapper.appendChild(hint);
                      }
                      return wrapper;
                    }

                    function createFieldWrapper(labelText, wide = false) {
                      const wrapper = document.createElement("div");
                      wrapper.className = wide ? "field wide" : "field";
                      const label = document.createElement("label");
                      label.textContent = labelText;
                      wrapper.appendChild(label);
                      return wrapper;
                    }

                    function parseInteger(value) {
                      if (value === "" || value == null) {
                        return null;
                      }
                      const parsed = Number.parseInt(value, 10);
                      return Number.isNaN(parsed) ? null : parsed;
                    }

                    function parseList(value) {
                      return value
                        .split(/[\n,]/)
                        .map(item => item.trim())
                        .filter(Boolean);
                    }

                    function toMultiline(values) {
                      return Array.isArray(values) ? values.join("\n") : "";
                    }

                    function emptyToNull(value) {
                      const trimmed = (value || "").trim();
                      return trimmed === "" ? null : trimmed;
                    }

                    function ensureNotification(trigger) {
                      if (!trigger.notification) {
                        trigger.notification = { enabled: false, template: "", delayMinutes: null };
                      }
                      return trigger.notification;
                    }

                    function setNestedDirection(trigger, key, value) {
                      if (!trigger.directionRange) {
                        trigger.directionRange = {};
                      }
                      trigger.directionRange[key] = value;
                    }

                    function buildPayload() {
                      const workflow = {
                        defaultSequenceCloseTimeoutMinutes: parseInteger(defaultSequenceCloseTimeoutInput.value) ?? 2880,
                        stages: state.config.workflow.stages.map(stage => ({
                          name: emptyToNull(stage.name),
                          labelTemplate: emptyToNull(stage.labelTemplate),
                          startMode: stage.startMode,
                          candidateTimeoutMinutes: stage.candidateTimeoutMinutes,
                          candidateCloseTimeoutMinutes: stage.candidateCloseTimeoutMinutes,
                          candidateCancelOnEvents: stage.candidateCancelOnEvents,
                          finishMode: stage.finishMode,
                          stickyCloseTimeoutMinutes: stage.stickyCloseTimeoutMinutes,
                          allowedNextStages: stage.allowedNextStages,
                          unexpectedNextStagePolicy: stage.unexpectedNextStagePolicy,
                          timeoutTransitionToStage: emptyToNull(stage.timeoutTransitionToStage),
                          sequenceCloseTimeoutMinutes: stage.sequenceCloseTimeoutMinutes,
                          saveStageAfterSequenceClosed: stage.saveStageAfterSequenceClosed !== false,
                          allowPartialFromFinish: Boolean(stage.allowPartialFromFinish),
                          startDuplicatePolicy: stage.startDuplicatePolicy,
                          finishDuplicatePolicy: stage.finishDuplicatePolicy,
                          intermediateStageOnTransition: emptyToNull(stage.intermediateStageOnTransition),
                          transitional: Boolean(stage.transitional),
                          sameStageReopenAfterMinutes: stage.sameStageReopenAfterMinutes,
                          startTriggers: sanitizeTriggers(stage.startTriggers),
                          finishTriggers: sanitizeTriggers(stage.finishTriggers)
                        }))
                      };
                      return { ...state.config, workflow };
                    }

                    function sanitizeTriggers(triggers) {
                      return triggers.map(trigger => {
                        const directionRange = trigger.directionRange && (trigger.directionRange.from != null || trigger.directionRange.to != null)
                          ? {
                              from: trigger.directionRange.from ?? null,
                              to: trigger.directionRange.to ?? null
                            }
                          : null;
                        const notification = trigger.notification && (
                          trigger.notification.enabled ||
                          emptyToNull(trigger.notification.template) !== null ||
                          trigger.notification.delayMinutes != null
                        )
                          ? {
                              enabled: Boolean(trigger.notification.enabled),
                              template: emptyToNull(trigger.notification.template),
                              delayMinutes: trigger.notification.delayMinutes
                            }
                          : null;
                        return {
                          name: emptyToNull(trigger.name),
                          cameraId: trigger.cameraId,
                          directionRange,
                          eventType: emptyToNull(trigger.eventType),
                          eventKey: emptyToNull(trigger.eventKey),
                          derivedStageInstance: emptyToNull(trigger.derivedStageInstance),
                          notification
                        };
                      });
                    }

                    async function saveConfiguration() {
                      clearStatus();
                      try {
                        const payload = buildPayload();
                        const response = await fetch("/config", {
                          method: "POST",
                          headers: {
                            "Content-Type": "application/json",
                            "Accept": "application/json"
                          },
                          body: JSON.stringify(payload)
                        });
                        if (!response.ok) {
                          showStatus("error", await response.text());
                          return;
                        }
                        const savedConfig = await response.json();
                        state.config = normalizeConfig(savedConfig);
                        render();
                        showStatus("success", "Configuration saved successfully.");
                      } catch (error) {
                        showStatus("error", error instanceof Error ? error.message : String(error));
                      }
                    }

                    function showStatus(type, message) {
                      statusBox.className = `status ${type}`;
                      statusBox.textContent = message;
                    }

                    function clearStatus() {
                      statusBox.className = "status";
                      statusBox.textContent = "";
                    }

                    function escapeHtml(value) {
                      return String(value)
                        .replaceAll("&", "&amp;")
                        .replaceAll("<", "&lt;")
                        .replaceAll(">", "&gt;")
                        .replaceAll('"', "&quot;")
                        .replaceAll("'", "&#39;");
                    }
                  </script>
                </body>
                </html>
                """.formatted(runtimeConfig.getConfigPath(), initialConfigJson);
    }
}
