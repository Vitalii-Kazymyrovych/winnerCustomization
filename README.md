# winnerCustomization

Spring Boot script that:

1. Reads ALPR detections from source PostgreSQL (`videoanalytics.alpr_detections` or configurable table).
2. Builds car movement sequences across configured camera stages using a repeatable stage timeline with recovery, Backyard, and delayed Test-Drive materialization.
3. Stores computed sequences in a separate PostgreSQL database asynchronously (does not block XLSX download response).
4. Exposes XLSX report download endpoint in a stage-row layout (dynamic per sequence, not fixed stage columns).
5. Runs DB-backed alert scheduling: pending alert jobs are stored in PostgreSQL and dispatched by background workers close to due time.
6. Provides manual trigger endpoint to force source-table pull with anti-parallel and cooldown protection.

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
5. Optional manual trigger (for external analytics callbacks):
   - `http://localhost:8080/source/trigger-pull`
   - returns `200` on success, `409` if a trigger is currently running, `429` during cooldown.
6. Download report from browser:
   - `http://localhost:8080/report/sequences.xlsx`
   - The same report file is additionally saved as `sequences.xlsx` in `reports.outputDirectory` (if configured).

## Configuration

Use `config.json` (not committed) with:

- Source DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Sequence DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Root PostgreSQL credentials for first-start bootstrap: `rootDatabase.host`, `rootDatabase.port`, `rootDatabase.user`, `rootDatabase.password`, optional `rootDatabase.maintenanceDb` (default `postgres`). Bootstrap now ensures both sequence database and its DB user/permissions.
- Source detections table name and optional `sourceTable.loadFrom` timestamp (`yyyy-MM-ddTHH:mm:ss`) to load only detections starting from the configured moment.
- Camera mapping lists for common points (Drive in / Service / Parking), plus transition cameras `driveInToService` and `serviceToDriveIn`; each camera is matched by `analyticsId + directionRange`.
  - `servicePosts` maps one camera per post with two direction ranges: `inDirectionRange` and `outDirectionRange`.
- Direction ranges per camera (`from`/`to`) where null means no filtering.
- Alert timing thresholds.
- `reports.outputDirectory`: optional folder where each `/report/sequences.xlsx` call also stores `sequences.xlsx`. The folder is created automatically if missing.
- Telegram notifications toggle and credentials.

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
- Repeated start events for an already-open stage are ignored as duplicates. The only overwrite exception is `Post Out`: a repeated `Post Out` updates the previous `Post` end and shifts the immediately-following `Service` start.
- Exit-only and recovery scenarios create valid partial stages with empty `In` and filled `Out` (`Drive In`, `Service`, `Post`, `Parking`). Partial stages never produce alerts.
- `Backyard` is a first-class stage. It starts on `Drive-In -> Service`, `Service Out`, or `Parking Out`, and ends on the next stage event. Repeated backyard triggers while `Backyard` is already active are ignored.
- `Test-Drive` starts as a candidate from `Drive-In Out` or `Service -> Drive-In`. It becomes a reportable stage only when the silence window from `timing.testDriveStartMinutes` elapses and the vehicle returns before the `timing.testDriveResetMinutes` timeout. If the absence reaches the timeout, the current sequence is closed and `Test-Drive` is omitted from the report.
- Sequences roll over after 48 hours without events for the same plate.
- If two detections for the same plate have the same timestamp, the later one is normalized to `+1 second` to keep ordering deterministic.
- Alerts are created only for full `Drive In` and `Service` stages and are attached to the specific stage row in the XLSX output.

## Notes

- Detailed console logging is enabled for runtime actions (config load, endpoint calls, source pull triggers, sequence build/storage, timed alert sync/dispatch, report generation, notifications).
- XLSX report endpoint now returns as soon as XLSX bytes are ready; sequence-table refresh runs in background to avoid browser download delay.
- XLSX report now contains two sheets:
  - `Sequences`: grouped stage layout with a plate marker row followed by stage rows (`Stage`, `Time in`, `Time out`, `Duration`, `Alerts`).
  - `Events`: flat stage layout with one row per stage and columns `Plate`, `Stage`, `In time`, `Out time`, `Duration`, `Alarms`.
- `Backyard` is emitted as a standalone stage whenever the car goes through `Drive-In -> Service` without reaching `Service in`, or leaves `Service out` without reaching `Service -> Drive-In` before another camera detection.
- Test-drive / left-territory reset logic now starts from `Drive in (out)` when no `Drive-In -> Service` follows, or from `Service -> Drive-In` when no `Drive in (in)` follows.
- Stage split is now: `Drive In` starts at `Drive in (in)` and ends at `Drive in (out)`, `Service` starts at `Service in` and ends at `Post in` (or next closing event), `Post` starts at `Post in` and ends at `Post out`, second `Service` starts at `Post out` and ends at `Service out` (or next closing event), `Backyard` spans from its trigger camera to the first subsequent camera detection.
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

- Post Out detections are treated as overwrite events: each new valid Post Out updates `postOutAt` and restarts second service start time.
