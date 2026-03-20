# winnerCustomization

Spring Boot script that:

1. Reads ALPR detections from source PostgreSQL (`videoanalytics.alpr_detections` or configurable table).
2. Builds car movement sequences across configured camera stages using a repeatable stage timeline with recovery, Backyard, and delayed Test-Drive materialization.
3. Stores computed sequences in a separate PostgreSQL database asynchronously (does not block XLSX download response).
4. Exposes XLSX report download endpoint in a stage-row layout (dynamic per sequence, not fixed stage columns).
5. Runs DB-backed alert scheduling: pending alert jobs are stored in PostgreSQL and dispatched by background workers close to due time.
6. Provides a live configuration UI/API at `/config` with a stage/trigger form editor for saving `config.json` without restarting the service.
7. Provides manual trigger endpoint to force source-table pull with anti-parallel and cooldown protection.

## Run

1. Build and run unit tests:
   ```bash
   ./mvnw -B test
   ```
2. Build jar:
   ```bash
   ./mvnw -B package
   ```
3. Put `config.json` near the jar (copy from `config.json.example` and fill values).
4. Start app:
   ```bash
   java -jar target/winnerCustomization-0.0.1-SNAPSHOT.jar
   ```
5. Edit runtime configuration in browser or via API:
   - Workflow editor: `http://localhost:8080/config`
   - Help page: `http://localhost:8080/config/help`
   - Technical task PDF: `http://localhost:8080/config/task.pdf`
   - JSON API: `GET /config`, `POST /config`
   - The HTML editor renders each workflow stage as a separate card with stage fields, `Start triggers`, and `Finish triggers`, plus `New Stage` / `New Trigger` buttons and delete actions.
   - The page preserves non-workflow sections of `config.json`; the browser serializes the edited workflow back to JSON and sends it as `application/json` to `POST /config`.
   - Successful saves rewrite `config.json` and update the in-memory configuration immediately without restart.
   - `workflow`, `sourceTable`, `notifications`, and `reports` changes affect subsequent sequence/report operations immediately.
   - Database connection changes (`sourceDatabase`, `sequenceDatabase`, `rootDatabase`) are persisted by the UI too, but existing JDBC beans are created only at startup, so DB host/port/user/password changes require an application restart.
6. Optional manual trigger (for external analytics callbacks):
   - `http://localhost:8080/source/trigger-pull`
   - returns `200` on success, `409` if a trigger is currently running, `429` during cooldown.
7. Download report from browser:
   - Full report: `http://localhost:8080/report/sequences.xlsx`
   - Date-scoped report for one calendar day: `http://localhost:8080/report/sequences.xlsx/dd-MM-yyyy`
   - The date-scoped endpoint uses only detections from `dd-MM-yyyy 00:00:00` up to, but not including, the next day `00:00:00` when forming sequences.
   - The full report is additionally saved as `sequences.xlsx` in `reports.outputDirectory` (if configured).
   - The date-scoped report is additionally saved as `sequences-dd-MM-yyyy.xlsx` in `reports.outputDirectory` (if configured).

## Configuration

Use `config.json` (not committed) with:

- Source DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Sequence DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Root PostgreSQL credentials for first-start bootstrap: `rootDatabase.host`, `rootDatabase.port`, `rootDatabase.user`, `rootDatabase.password`, optional `rootDatabase.maintenanceDb` (default `postgres`). Bootstrap now ensures both sequence database and its DB user/permissions.
- Source detections table name and optional `sourceTable.loadFrom` timestamp (`yyyy-MM-ddTHH:mm:ss`) to load only detections starting from the configured moment.
- `workflow`: declarative stage/trigger model used by the configuration screen. Each stage can define `name`, `labelTemplate`, `startTriggers`, `finishTriggers`, `startMode`, `finishMode`, candidate/sticky timeout settings, duplicate handling, transition stage references, booleans such as `transitional` / `allowPartialFromFinish`, list fields such as `allowedNextStages` / `candidateCancelOnEvents`, and per-trigger notification settings.
- `workflow` is now the preferred and sufficient way to describe business logic; a config that already contains `workflow.stages[]` does **not** need the legacy `cameras` block.
- Legacy `cameras` + `timing` are still supported only as a backward-compatible fallback. If `workflow` is omitted, the application derives an equivalent default workflow from those legacy blocks so old configs continue to work.
- Legacy camera fallback fields, when used, are: common camera lists for Drive in / Service / Parking, transition cameras `driveInToService` and `serviceToDriveIn`, plus `servicePosts` with `inDirectionRange` / `outDirectionRange`.
  - `postName` is also reused as the visible stage label in both XLSX sheets, so report rows show `Post 1` / `Post 2` instead of the generic `Post` label.
  - Direction ranges per camera (`from`/`to`) where null means no filtering. Ranges support wrap-around through `0` degrees (`270 -> 90`) and use an exclusive upper bound, so adjacent ranges can safely share borders without double-classifying `90`/`270`.
- `reports.outputDirectory`: optional folder where `/report/sequences.xlsx` stores `sequences.xlsx`, and `/report/sequences.xlsx/dd-MM-yyyy` stores `sequences-dd-MM-yyyy.xlsx`. The folder is created automatically if missing.
- Telegram notifications toggle and credentials.
- `GET /config` returns the effective runtime config, so operators can inspect the generated workflow before editing it. The HTML representation also pre-populates the stage/trigger cards from the same runtime config.
- `GET /config/help` returns a static instruction page that explains Stage/Trigger semantics, mode/policy meanings, required stages (`drive_in`, `service`, `parking`), transitional stages, and links to the bundled PDF task.
- `GET /config/task.pdf` streams the project technical task PDF directly from the application working directory.
- `POST /config` validates required fields, positive timeouts, unique stage names, supported mode/policy values, and references such as `allowedNextStages`, `timeoutTransitionToStage`, and `intermediateStageOnTransition` before saving.

### What is required for startup

At minimum, a practical startup config must contain:

- `sourceDatabase.host|port|db|schema|user|password`
- `sequenceDatabase.host|port|db|schema|user|password`
- `rootDatabase.host|port|user|password` (required by the current startup bootstrap that ensures the sequence DB exists)
- `sourceTable.table`
- either:
  - `workflow.defaultSequenceCloseTimeoutMinutes` + `workflow.stages[]`, or
  - legacy `cameras` (optionally with `timing`) so the app can synthesize `workflow`

Optional at startup:

- `sourceTable.loadFrom`
- `notifications`
- `reports.outputDirectory`
- `timing` when `workflow` is already fully defined

## Timed notifications (DB-backed)

Notifications are now processed independently from report download:

- Background sync worker (`alerts.sync.delay.millis`, default `10000`) rebuilds sequences from source detections and upserts/cancels pending alert jobs in `alert_jobs`.
- Background dispatch worker (`alerts.dispatch.delay.millis`, default `5000`) sends only due jobs (`status=PENDING and due_at <= now`) and marks them as `SENT`.
- Per-stage completion cancels pending jobs, so no alert is sent if the car reached the expected next stage in time.
- `alert_jobs` has a partial index on pending due rows and a unique key (`plate_number`, `alert_type`, `trigger_at`) to avoid duplicate job explosion.

This keeps DB load bounded: each cycle does one read/build pass and a small indexed due-job query, while writes are idempotent upserts/cancels for active sequences only.

## Trigger endpoint behavior

- Endpoint: `GET /source/trigger-pull`.
- Performs immediate read from source detections table.
- Protection from duplicate/parallel calls:
  - only one running trigger at a time;
  - 30-second cooldown after successful trigger.
- Suitable for situations where multiple analytics systems can call the endpoint simultaneously.

## Startup troubleshooting

- If startup fails with `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`, ensure you are running a build that includes `JacksonConfig` (adds explicit `ObjectMapper` bean for runtime config loading).
- If startup fails with `Java 8 date/time type java.time.LocalDateTime not supported by default` while reading `sourceTable.loadFrom`, ensure the build includes `jackson-datatype-jsr310` (current `pom.xml` includes it).
- On first start, the app attempts to auto-create `sequenceDatabase.db` using `rootDatabase` credentials.
- If startup fails with `Incorrect result size: expected 1, actual 0` while checking `pg_database`, upgrade to a build that uses `select exists(...)` for database presence checks (current implementation handles missing DB correctly).
- If startup/report still fails with sequence DB connection errors, check:
  1. `rootDatabase` credentials really have rights to read `pg_database` and `CREATE DATABASE`.
  2. `rootDatabase.host`/`port` point to the same PostgreSQL instance as `sequenceDatabase`.
  3. `sequenceDatabase.user`/`password` are set correctly. Current bootstrap will create/update this role and grant DB/schema permissions automatically when root credentials are valid.
  4. Manual validation:
     - `psql -h <rootHost> -p <rootPort> -U <rootUser> -d postgres -c "select datname from pg_database"`
     - `psql -h <seqHost> -p <seqPort> -U <seqUser> -d <sequenceDb>`

## Sequence processing rules

The engine is now workflow-driven: stage names, labels, triggers, candidate/sticky behavior, duplicate policies, and transition references come from `workflow.stages[]`, not from a hardcoded enum or `switch` by camera type.

Key rules:

- One plate keeps one active sequence, but the sequence may contain any number of stage windows with arbitrary config-defined names such as `service_primary`, `post_3`, or `parking_secondary`.
- Every detection is resolved through configured triggers (`cameraId + directionRange`) into a stage start/finish event. If `workflow` is omitted, the application derives an equivalent default workflow from legacy camera blocks.
- `startMode=immediate` opens a stage right away. `startMode=candidate` creates a pending candidate that materializes only after `candidateTimeoutMinutes` of silence; configured `candidateCancelOnEvents` cancel it immediately.
- `finishMode=sticky` keeps the stage open after `finishTrigger`: repeated finish events refresh the sticky close timestamp, and `timeoutTransitionToStage` may materialize a transitional stage after `stickyCloseTimeoutMinutes`.
- `allowedNextStages` and `unexpectedNextStagePolicy` are honored for every stage. Unsupported next events can now be ignored, converted into partial stages, or wrapped with an intermediate stage instead of always forcing the same hardcoded transition.
- `intermediateStageOnTransition` and `transitional=true` allow explicit transitional stages such as `Backyard` to be inserted by configuration, including on unexpected transitions.
- `allowPartialFromFinish=true` lets any stage create a partial row (`In` empty, `Out` filled) when only a finish event is observed.
- Duplicate handling is configurable through `startDuplicatePolicy`, `finishDuplicatePolicy`, and `sameStageReopenAfterMinutes`.
- Sequence close timeout can be defined globally (`workflow.defaultSequenceCloseTimeoutMinutes`) and overridden per stage with `sequenceCloseTimeoutMinutes`.
- If two detections for the same plate have the same timestamp, the later one is normalized to `+1 second` to keep ordering deterministic.
- Alerts are evaluated from trigger-level notification settings, so delay/template settings are now defined in the workflow config itself.

## Notes

- Detailed console logging is enabled for runtime actions (config load, endpoint calls, source pull triggers, sequence build/storage, timed alert sync/dispatch, report generation, notifications).
- XLSX report endpoints now return as soon as XLSX bytes are ready; sequence-table refresh runs in background to avoid browser download delay.
- The dated endpoint builds sequences strictly from detections that fall inside the requested calendar day window (`00:00:00` inclusive to next-day `00:00:00` exclusive).
- XLSX report now contains two sheets:
  - `Sequences`: grouped stage layout with a plate marker row followed by stage rows (`Stage`, `Time in`, `Time out`, `Duration`, `Alerts`). Post rows display the configured `servicePosts[].postName`. After every closed non-empty sequence the sheet also appends a `Sequence Closed` row with `finishedAt` in the `Time out` column.
  - `Events`: flat stage layout with one row per stage and columns `Plate`, `Stage`, `In time`, `Out time`, `Duration`, `Alarms`. Post rows also display the configured post name.
- All XLSX timestamps are rendered as `yyyy-MM-dd HH:mm:ss` (without `T` separator or fractional seconds) for easier manual comparison with DB exports.
- Records without stage windows are skipped in both sheets; the report never emits `No stages`.
- `Backyard` is emitted as a standalone stage whenever the car goes through `Drive-In -> Service` without reaching `Service in`, or leaves `Service out` without reaching `Service -> Drive-In` before another camera detection.
- Test-drive / left-territory reset logic now starts from `Drive in (out)` when no `Drive-In -> Service` follows, or from `Service -> Drive-In` when no `Drive in (in)` follows.
- Stage split is now: `Drive In` starts at `Drive in (in)` and ends at `Drive in (out)`, `Service` starts at `Service in` and ends at `Post in` (or next closing event), `Post` starts at `Post in` and keeps running across duplicate `Post In` / `Post Out` events for the same configured post until another stage closes it, synthetic `Service` may fill the gap after sticky `Post`, and `Backyard` spans from its trigger camera to the first subsequent camera detection.
- Open sticky `Post` rows are still rendered in both XLSX sheets with empty `Out time`; their `Duration` is calculated as `Sequence finishedAt - Post in`, so duplicates on the same post still contribute to visible duration even without a closing stage.
- `config.json` is in `.gitignore`.
- Use `config.json.example` as the template.
- The app creates `vehicle_sequences` table in sequence DB if it does not exist.

## Tests

Unit tests cover sequence orchestration and every service class:
- `SequenceEngineTest`
- `DetectionServiceTest`
- `SequenceStorageServiceTest`
- `ReportServiceTest`
- `TelegramNotifierTest`
- `SourcePullTriggerServiceTest`
- `AlertSchedulerServiceTest`

Unit tests remain offline and use mocks.

Additionally, `PostgresDatabaseOperationsIntegrationTest` validates end-to-end PostgreSQL operations against a local PostgreSQL instance (`localhost:5432`, user/password `postgres/postgres`). If PostgreSQL is unavailable in CI/local env, the test is skipped via JUnit assumption:
- sequence DB auto-creation via `DatabaseBootstrapService`;
- source detection read via `DetectionService`;
- sequence table initialization and write flow via `SequenceStorageService`.

- Sticky `Post Out` detections are treated as candidates, not immediate transitions: each new valid `Post Out` refreshes the future close timestamp used when the sticky `Post` is eventually closed by another stage or by sequence finalization.
