# winnerCustomization

Spring Boot script that:

1. Reads ALPR detections from source PostgreSQL (`videoanalytics.alpr_detections` or configurable table).
2. Builds car movement sequences across configured camera stages using a repeatable stage timeline with recovery, Backyard, and delayed Test-Drive materialization.
3. Stores computed sequences in a separate PostgreSQL database asynchronously (does not block XLSX download response).
4. Exposes XLSX report download endpoint in a stage-row layout (dynamic per sequence, not fixed stage columns).
5. Runs DB-backed alert scheduling: pending alert jobs are stored in PostgreSQL and dispatched by background workers close to due time.
6. Provides a simple live configuration UI/API at `/config` for viewing and saving `config.json` without restarting the service.
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
   - HTML editor: `http://localhost:8080/config`
   - JSON API: `GET /config`, `POST /config`
   - Successful saves rewrite `config.json` and update the in-memory configuration immediately without restart.
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
- Camera mapping lists for common points (Drive in / Service / Parking), plus transition cameras `driveInToService` and `serviceToDriveIn`; each camera is matched by `analyticsId + directionRange`.
- `servicePosts` maps one camera per post with two direction ranges: `inDirectionRange` and `outDirectionRange`.
  - `postName` is also reused as the visible stage label in both XLSX sheets, so report rows show `Post 1` / `Post 2` instead of the generic `Post` label.
- Direction ranges per camera (`from`/`to`) where null means no filtering. Ranges support wrap-around through `0` degrees (`270 -> 90`) and use an exclusive upper bound, so adjacent ranges can safely share borders without double-classifying `90`/`270`.
- Alert timing thresholds.
- `reports.outputDirectory`: optional folder where `/report/sequences.xlsx` stores `sequences.xlsx`, and `/report/sequences.xlsx/dd-MM-yyyy` stores `sequences-dd-MM-yyyy.xlsx`. The folder is created automatically if missing.
- Telegram notifications toggle and credentials.

- `workflow`: declarative stage/trigger model used by the new configuration screen. Each stage can define `name`, `labelTemplate`, `startTriggers`, `finishTriggers`, candidate/sticky timeout settings, duplicate handling, transition stage references, and per-trigger notification settings. If `workflow` is omitted, the application derives an equivalent default workflow from the legacy `cameras` + `timing` blocks so existing configs continue to work.
- `GET /config` returns the effective runtime config, so operators can inspect the generated workflow before editing it.
- `POST /config` validates required fields, positive timeouts, unique stage names, and references such as `allowedNextStages`, `timeoutTransitionToStage`, and `intermediateStageOnTransition` before saving.

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

The current engine interprets detections as a chronological sequence of stage occurrences, not as a fixed set of one-off timestamps. Key rules:

- Only one stage can be active at a time; the next stage closes the previous one at `eventTime - 1 second`.
- The same stage may appear multiple times in the same sequence (`Service`, `Post`, `Backyard`, etc.). Report rows are never merged.
- Repeated start events for an already-open stage are ignored as duplicates.
- `Post` now uses sticky logic per post name/camera mapping: while `Post N` is active, repeated `Post N In` is ignored, repeated `Post N Out` only refreshes an internal `outTimeCandidate`, and the stage is closed only when another stage is detected or when the sequence is finalized.
- When sticky `Post` closes and an explicit `Service In` is missing, the engine may synthesize an intermediate `Service` stage from `Post Out + 1 second` up to the next closing event. `Post Out` no longer auto-opens `Service` by itself.
- Exit-only and recovery scenarios create valid partial stages with empty `In` and filled `Out` (`Drive In`, `Service`, `Post`, `Parking`). Partial stages never produce alerts, and `Service Out` / `Parking Out` are still recorded as partial recovery rows even if the vehicle is already inside `Backyard`.
- `Post Out` recovery no longer opens `Service` immediately. The engine now keeps that exit-only post sticky until a later non-post event proves a real return to service; repeated `Post Out` and a later same-post `Post In` stay `Post -> Post` instead of producing noisy `Post -> Service -> Post`.
- `Backyard` is a first-class stage. It starts on `Drive-In -> Service`, `Service Out`, or `Parking Out`, and ends on the next stage event. Repeated backyard triggers while `Backyard` is already active are ignored, but exit-only detections inside an already-open `Backyard` still append partial recovery rows without closing `Backyard`.
- `Test-Drive` starts as a candidate from `Drive-In Out` or `Service -> Drive-In`. `Service -> Drive-In` first closes the active stage, so `Service` and `Test-Drive` never overlap. `Test-Drive` becomes a reportable stage only when the silence window from `timing.testDriveStartMinutes` elapses and the vehicle returns before the `timing.testDriveResetMinutes` timeout. If the absence reaches the timeout, the current sequence is closed and `Test-Drive` is omitted from the report.
- Sequences roll over after 48 hours without events for the same plate.
- If two detections for the same plate have the same timestamp, the later one is normalized to `+1 second` to keep ordering deterministic.
- Alerts are created only for full `Drive In` and `Service` stages and are attached to the specific stage row in the XLSX output.
- Transition-only chains that never materialize any real stage are dropped from the final output instead of appearing as synthetic `No stages` rows.

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
