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
                <html lang=\"uk\">
                <head>
                  <meta charset=\"utf-8\" />
                  <title>winnerCustomization config</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --bg: #f5f7fb;
                      --card: #ffffff;
                      --border: #d9e1ec;
                      --text: #1f2937;
                      --muted: #5b6472;
                      --accent: #2563eb;
                      --accent-soft: #dbeafe;
                    }
                    body {
                      font-family: Arial, sans-serif;
                      margin: 0;
                      background: var(--bg);
                      color: var(--text);
                    }
                    main {
                      max-width: 1200px;
                      margin: 0 auto;
                      padding: 2rem;
                    }
                    .layout {
                      display: grid;
                      grid-template-columns: minmax(0, 2fr) minmax(18rem, 1fr);
                      gap: 1.5rem;
                      align-items: start;
                    }
                    .card {
                      background: var(--card);
                      border: 1px solid var(--border);
                      border-radius: 16px;
                      padding: 1.5rem;
                      box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
                    }
                    h1, h2, h3 {
                      margin-top: 0;
                    }
                    p, li {
                      line-height: 1.6;
                    }
                    .muted {
                      color: var(--muted);
                    }
                    .actions {
                      display: flex;
                      flex-wrap: wrap;
                      gap: 0.75rem;
                      margin-bottom: 1rem;
                    }
                    .button, button {
                      display: inline-block;
                      border: 0;
                      border-radius: 10px;
                      background: var(--accent);
                      color: white;
                      text-decoration: none;
                      padding: 0.8rem 1.1rem;
                      font-size: 1rem;
                      cursor: pointer;
                    }
                    .button.secondary {
                      background: var(--accent-soft);
                      color: var(--accent);
                    }
                    textarea {
                      width: 100%%;
                      min-height: 38rem;
                      box-sizing: border-box;
                      border: 1px solid var(--border);
                      border-radius: 12px;
                      padding: 1rem;
                      font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
                      font-size: 0.95rem;
                      line-height: 1.5;
                      resize: vertical;
                    }
                    code {
                      background: #eef2ff;
                      padding: 0.15rem 0.35rem;
                      border-radius: 6px;
                    }
                    ul {
                      padding-left: 1.2rem;
                    }
                    @media (max-width: 960px) {
                      main {
                        padding: 1rem;
                      }
                      .layout {
                        grid-template-columns: 1fr;
                      }
                    }
                  </style>
                </head>
                <body>
                  <main>
                    <div class=\"layout\">
                      <section class=\"card\">
                        <h1>Редагування конфігурації</h1>
                        <p class=\"muted\">Зміни зберігаються у <code>config.json</code> та одразу оновлюють runtime-конфігурацію. Зміни доступні для наступних pull/report операцій без рестарту, окрім перевідкриття JDBC-підключень.</p>
                        <div class=\"actions\">
                          <a class=\"button secondary\" href=\"/config/help\">Детальна інструкція українською</a>
                          <a class=\"button secondary\" href=\"/config\" target=\"_blank\" rel=\"noreferrer\">Відкрити JSON в новій вкладці</a>
                        </div>
                        <form method=\"post\" action=\"/config\">
                          <textarea name=\"json\">%s</textarea>
                          <br />
                          <button type=\"submit\">Зберегти конфігурацію</button>
                        </form>
                      </section>
                      <aside class=\"card\">
                        <h2>Що змінюється одразу</h2>
                        <ul>
                          <li><code>workflow</code> — правила етапів, таймаути, переходи та нотифікації.</li>
                          <li><code>sourceTable</code> — джерело детекцій і фільтр <code>loadFrom</code>.</li>
                          <li><code>notifications</code> — увімкнення/вимкнення Telegram та шаблони алертів.</li>
                          <li><code>reports</code> — куди зберігати згенеровані XLSX-файли.</li>
                        </ul>
                        <h3>Що потребує рестарту</h3>
                        <p class=\"muted\">Поля <code>sourceDatabase</code>, <code>sequenceDatabase</code> і <code>rootDatabase</code> теж можна зберегти через UI, але нові хости/паролі почнуть діяти лише після перезапуску застосунку, бо datasource-би створюються під час старту.</p>
                        <h3>Рекомендований порядок</h3>
                        <ol>
                          <li>Спочатку відредагуйте копію <code>config.json.example</code>.</li>
                          <li>Перевірте JSON-структуру та значення таймаутів.</li>
                          <li>Після збереження відкрийте <code>/config/help</code> і звіртеся з прикладами секцій.</li>
                        </ol>
                      </aside>
                    </div>
                  </main>
                </body>
                </html>
                """.formatted(json));
    }

    @GetMapping(value = "/config/help", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getConfigHelpHtml() {
        return htmlResponse("""
                <!doctype html>
                <html lang=\"uk\">
                <head>
                  <meta charset=\"utf-8\" />
                  <title>Допомога з конфігурацією</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --bg: #f5f7fb;
                      --card: #ffffff;
                      --border: #d9e1ec;
                      --text: #1f2937;
                      --muted: #5b6472;
                      --accent: #2563eb;
                      --accent-soft: #eff6ff;
                      --warning: #92400e;
                      --warning-bg: #fff7ed;
                    }
                    body {
                      margin: 0;
                      font-family: Arial, sans-serif;
                      background: var(--bg);
                      color: var(--text);
                    }
                    main {
                      max-width: 1100px;
                      margin: 0 auto;
                      padding: 2rem;
                    }
                    .hero, .card {
                      background: var(--card);
                      border: 1px solid var(--border);
                      border-radius: 18px;
                      padding: 1.5rem;
                      box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
                    }
                    .hero {
                      margin-bottom: 1.5rem;
                    }
                    .grid {
                      display: grid;
                      grid-template-columns: repeat(2, minmax(0, 1fr));
                      gap: 1rem;
                    }
                    .full {
                      grid-column: 1 / -1;
                    }
                    h1, h2, h3 {
                      margin-top: 0;
                    }
                    p, li {
                      line-height: 1.65;
                    }
                    .muted {
                      color: var(--muted);
                    }
                    .pill {
                      display: inline-block;
                      padding: 0.25rem 0.65rem;
                      border-radius: 999px;
                      background: var(--accent-soft);
                      color: var(--accent);
                      margin-right: 0.5rem;
                      margin-bottom: 0.5rem;
                      font-size: 0.92rem;
                    }
                    .warning {
                      background: var(--warning-bg);
                      border-left: 4px solid #f59e0b;
                      padding: 1rem;
                      color: var(--warning);
                      border-radius: 12px;
                    }
                    code, pre {
                      font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
                    }
                    code {
                      background: #eef2ff;
                      padding: 0.15rem 0.35rem;
                      border-radius: 6px;
                    }
                    pre {
                      white-space: pre-wrap;
                      overflow-x: auto;
                      background: #0f172a;
                      color: #e2e8f0;
                      border-radius: 14px;
                      padding: 1rem;
                    }
                    a.button {
                      display: inline-block;
                      margin-top: 0.75rem;
                      text-decoration: none;
                      background: var(--accent);
                      color: white;
                      padding: 0.75rem 1rem;
                      border-radius: 10px;
                    }
                    ul, ol {
                      padding-left: 1.2rem;
                    }
                    table {
                      width: 100%%;
                      border-collapse: collapse;
                      margin-top: 0.75rem;
                    }
                    th, td {
                      border: 1px solid var(--border);
                      padding: 0.75rem;
                      text-align: left;
                      vertical-align: top;
                    }
                    th {
                      background: #f8fafc;
                    }
                    @media (max-width: 900px) {
                      main {
                        padding: 1rem;
                      }
                      .grid {
                        grid-template-columns: 1fr;
                      }
                    }
                  </style>
                </head>
                <body>
                  <main>
                    <section class=\"hero\">
                      <h1>/config/help — як налаштовувати конфігурацію</h1>
                      <p>Ця сторінка пояснює, як безпечно редагувати <code>config.json</code> для сервісу <strong>winnerCustomization</strong>. Вона орієнтована на операторів і адміністраторів, які працюють через веб-інтерфейс <code>/config</code>.</p>
                      <div>
                        <span class=\"pill\">Мова: українська</span>
                        <span class=\"pill\">Формат: JSON</span>
                        <span class=\"pill\">Маршрут: /config/help</span>
                      </div>
                      <a class=\"button\" href=\"/config\">Повернутися до редактора конфігурації</a>
                    </section>

                    <div class=\"grid\">
                      <section class=\"card full\">
                        <h2>1. Загальний порядок налаштування</h2>
                        <ol>
                          <li>Створіть локальний <code>config.json</code> на основі <code>config.json.example</code>.</li>
                          <li>Заповніть блоки підключення до БД: <code>sourceDatabase</code>, <code>sequenceDatabase</code>, <code>rootDatabase</code>.</li>
                          <li>Вкажіть джерело детекцій у <code>sourceTable.table</code> та, за потреби, нижню межу <code>sourceTable.loadFrom</code>.</li>
                          <li>Налаштуйте <code>workflow</code>: етапи, тригери, дозволені переходи, candidate/sticky-поведінку, таймаути та правила алертів.</li>
                          <li>Опційно додайте <code>notifications</code> і <code>reports.outputDirectory</code>.</li>
                          <li>Збережіть конфіг через <code>/config</code> або покладіть файл поруч із jar перед запуском.</li>
                        </ol>
                        <div class=\"warning\">
                          Не зберігайте реальні токени або паролі в <code>config.json.example</code> і не комітьте <code>config.json</code> у git. Робочий файл повинен залишатися локальним для конкретного середовища.
                        </div>
                      </section>

                      <section class=\"card\">
                        <h2>2. Які блоки обов'язкові</h2>
                        <table>
                          <thead>
                            <tr><th>Блок</th><th>Для чого потрібен</th></tr>
                          </thead>
                          <tbody>
                            <tr><td><code>sourceDatabase</code></td><td>Доступ до таблиці з ALPR-детекціями.</td></tr>
                            <tr><td><code>sequenceDatabase</code></td><td>База, в якій зберігаються побудовані послідовності та alert jobs.</td></tr>
                            <tr><td><code>rootDatabase</code></td><td>Адмін-доступ для bootstrap: створення/оновлення ролі та БД sequenceDatabase на старті.</td></tr>
                            <tr><td><code>sourceTable.table</code></td><td>Назва таблиці джерела, з якої читаються детекції.</td></tr>
                            <tr><td><code>workflow</code></td><td>Основна бізнес-логіка етапів. Це рекомендований сучасний формат конфігурації.</td></tr>
                          </tbody>
                        </table>
                        <p class=\"muted\">Legacy-блоки <code>cameras</code> і <code>timing</code> підтримуються як fallback, але для нових конфігурацій краще відразу задавати <code>workflow</code>.</p>
                      </section>

                      <section class=\"card\">
                        <h2>3. Що застосовується без рестарту</h2>
                        <ul>
                          <li><code>workflow</code> — нові правила почнуть діяти для наступних побудов послідовностей.</li>
                          <li><code>sourceTable</code> — одразу впливає на майбутні завантаження детекцій.</li>
                          <li><code>notifications</code> — одразу впливає на відправку алертів.</li>
                          <li><code>reports.outputDirectory</code> — одразу впливає на нові XLSX-файли.</li>
                        </ul>
                        <p class=\"muted\">Параметри підключення до БД також збережуться, але нові datasource не створюються на льоту, тому після зміни доступів або хостів потрібен restart застосунку.</p>
                      </section>

                      <section class=\"card full\">
                        <h2>4. Структура JSON по секціях</h2>
                        <h3><code>sourceDatabase</code>, <code>sequenceDatabase</code></h3>
                        <p>Обидва блоки мають однакову структуру: <code>host</code>, <code>port</code>, <code>db</code>, <code>schema</code>, <code>user</code>, <code>password</code>. У <code>sourceDatabase</code> сервіс читає детекції, а в <code>sequenceDatabase</code> — зберігає результати.</p>
                        <h3><code>rootDatabase</code></h3>
                        <p>Потрібен тільки для стартового bootstrap. Містить <code>host</code>, <code>port</code>, <code>user</code>, <code>password</code> і необов'язковий <code>maintenanceDb</code> (типово <code>postgres</code>).</p>
                        <h3><code>sourceTable</code></h3>
                        <p><code>table</code> — ім'я таблиці джерела. <code>loadFrom</code> — необов'язковий timestamp у форматі <code>yyyy-MM-ddTHH:mm:ss</code>, який обрізає історичні дані, щоб не перечитувати дуже старі детекції.</p>
                        <h3><code>notifications</code></h3>
                        <p>Містить <code>enabled</code>, <code>telegramBotToken</code>, <code>telegramChatId</code>. Якщо <code>enabled=false</code>, відправка в Telegram не виконується.</p>
                        <h3><code>reports</code></h3>
                        <p>Зараз основне поле — <code>outputDirectory</code>. Якщо воно задане, кожен згенерований XLSX не лише повертається через HTTP, а й зберігається на диск.</p>
                      </section>

                      <section class=\"card full\">
                        <h2>5. Як мислити про <code>workflow</code></h2>
                        <p><code>workflow</code> складається з глобального таймауту <code>defaultSequenceCloseTimeoutMinutes</code> і списку <code>stages</code>. Кожен stage описує окремий логічний етап руху авто.</p>
                        <table>
                          <thead>
                            <tr><th>Поле stage</th><th>Пояснення</th></tr>
                          </thead>
                          <tbody>
                            <tr><td><code>name</code></td><td>Унікальний технічний ідентифікатор етапу, наприклад <code>drive_in</code> або <code>service</code>.</td></tr>
                            <tr><td><code>labelTemplate</code></td><td>Людська назва, яка потрапляє у звіти.</td></tr>
                            <tr><td><code>startTriggers</code> / <code>finishTriggers</code></td><td>Описують, які камери та напрямки відкривають або закривають stage.</td></tr>
                            <tr><td><code>startMode</code></td><td><code>immediate</code> — stage стартує відразу; <code>candidate</code> — лише після таймауту тиші.</td></tr>
                            <tr><td><code>finishMode</code></td><td><code>immediate</code> — stage закривається одразу; <code>sticky</code> — закриття відкладається до наступної події або sticky-таймауту.</td></tr>
                            <tr><td><code>allowedNextStages</code></td><td>Список етапів, на які дозволено переходити без конфлікту.</td></tr>
                            <tr><td><code>unexpectedNextStagePolicy</code></td><td>Що робити з неочікуваним переходом: ігнорувати, починати partial stage, вставляти intermediate stage тощо.</td></tr>
                            <tr><td><code>sequenceCloseTimeoutMinutes</code></td><td>Персональний таймаут закриття послідовності для цього stage.</td></tr>
                          </tbody>
                        </table>
                        <p>Якщо ви мігруєте зі старого формату, сервіс може згенерувати <code>workflow</code> з <code>cameras</code> і <code>timing</code>, але в UI краще вже редагувати саме згенерований <code>workflow</code>.</p>
                      </section>

                      <section class=\"card\">
                        <h2>6. Що таке trigger</h2>
                        <p>Trigger у stage — це правило зіставлення детекції до події workflow.</p>
                        <ul>
                          <li><code>cameraId</code> — ID аналітики/камери.</li>
                          <li><code>directionRange.from/to</code> — необов'язковий діапазон напряму руху.</li>
                          <li><code>eventType</code> — додаткова класифікація події, якщо використовується.</li>
                          <li><code>eventKey</code> — технічна назва події для логіки та candidate-cancel правил.</li>
                          <li><code>notification</code> — правило алерта, яке запускається від цього тригера.</li>
                        </ul>
                      </section>

                      <section class=\"card\">
                        <h2>7. Candidate, sticky та partial — простими словами</h2>
                        <ul>
                          <li><strong>candidate</strong>: подія не відкриває stage миттєво, а лише створює кандидат. Якщо протягом <code>candidateTimeoutMinutes</code> не сталося скасування, stage матеріалізується.</li>
                          <li><strong>sticky</strong>: навіть після finish-trigger етап ще залишається відкритим. Це корисно для Post, коли кілька дубльованих вихідних детекцій не повинні одразу породжувати новий етап.</li>
                          <li><strong>partial</strong>: дозволяє створити рядок звіту навіть коли є лише фінальна подія без стартової, наприклад recovery після пропущеної детекції.</li>
                        </ul>
                      </section>

                      <section class=\"card full\">
                        <h2>8. Приклад мінімального сучасного конфіга</h2>
                        <pre>{
  "sourceDatabase": {
    "host": "localhost",
    "port": 5432,
    "db": "source_database_name",
    "schema": "videoanalytics",
    "user": "postgres",
    "password": "change-me"
  },
  "sequenceDatabase": {
    "host": "localhost",
    "port": 5432,
    "db": "sequences_database_name",
    "schema": "public",
    "user": "sequence_user",
    "password": "change-me"
  },
  "rootDatabase": {
    "host": "localhost",
    "port": 5432,
    "user": "postgres",
    "password": "change-me"
  },
  "sourceTable": {
    "table": "alpr_detections",
    "loadFrom": "2026-03-01T00:00:00"
  },
  "reports": {
    "outputDirectory": "./reports-output"
  },
  "notifications": {
    "enabled": false,
    "telegramBotToken": "",
    "telegramChatId": ""
  },
  "workflow": {
    "defaultSequenceCloseTimeoutMinutes": 2880,
    "stages": [
      {
        "name": "drive_in",
        "labelTemplate": "Drive In",
        "startMode": "immediate",
        "finishMode": "immediate",
        "startTriggers": [
          {
            "cameraId": 1001,
            "eventKey": "DRIVE_IN_IN",
            "name": "Drive in gate"
          }
        ],
        "finishTriggers": [
          {
            "cameraId": 1002,
            "eventKey": "DRIVE_IN_OUT"
          }
        ],
        "allowedNextStages": ["service", "parking"]
      }
    ]
  }
}</pre>
                        <p class=\"muted\">Реальний конфіг зазвичай містить більше stage-ів, включно з Post, Backyard, Parking, transition cameras та notification rules.</p>
                      </section>

                      <section class=\"card full\">
                        <h2>9. Типові помилки та як їх уникнути</h2>
                        <ul>
                          <li>Неунікальні <code>workflow.stages[].name</code> — кожен stage повинен мати власну унікальну назву.</li>
                          <li>Посилання на stage, якого не існує — перевіряйте <code>allowedNextStages</code>, <code>timeoutTransitionToStage</code>, <code>intermediateStageOnTransition</code>.</li>
                          <li>Від'ємні або нульові таймаути — значення мають бути додатними там, де сервіс очікує minutes.</li>
                          <li>Неправильний timestamp у <code>loadFrom</code> — використовуйте формат <code>yyyy-MM-ddTHH:mm:ss</code>.</li>
                          <li>Очікування, що зміна DB credentials запрацює без рестарту — для цього потрібен restart.</li>
                        </ul>
                      </section>

                      <section class=\"card full\">
                        <h2>10. Коли використовувати legacy <code>cameras</code></h2>
                        <p>Тільки коли ви підтримуєте старий конфіг або переносите вже існуюче production-середовище. Для нових інсталяцій рекомендується повністю описувати логіку через <code>workflow</code>.</p>
                        <p class=\"muted\">Якщо legacy-блоки все ж присутні, сервіс згенерує з них workflow-модель автоматично. Це зручно для міграції, але ускладнює ручне редагування, тому після першого відкриття через <code>/config</code> краще поступово перейти на явний workflow.</p>
                      </section>
                    </div>
                  </main>
                </body>
                </html>
                """);
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
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(getConfigHtml().getBody());
    }


    private ResponseEntity<String> htmlResponse(String html) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
