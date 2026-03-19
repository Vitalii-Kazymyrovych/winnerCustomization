# Technical specification

## Components

### `JacksonConfig`
- Provides explicit `ObjectMapper` bean for JSON serialization/deserialization dependencies.
- Uses `findAndRegisterModules()` and relies on `jackson-datatype-jsr310` on classpath, so runtime config can deserialize Java time values (`LocalDateTime` such as `sourceTable.loadFrom`).
- Guarantees `RuntimeConfig` constructor injection works during application startup.

### `RuntimeConfig`
- Loads runtime JSON configuration from `<working_dir>/config.json` at startup.
- Provides typed `AppConfig` object for all services.

### `JdbcConfig`
- Creates two PostgreSQL data sources and JDBC templates:
  - `sourceJdbc`: reads detections.
  - `sequenceJdbc`: stores computed sequences.
- Before creating `sequenceDataSource`, invokes `DatabaseBootstrapService.ensureDatabaseExists(...)`.

### `DatabaseBootstrapService`
- Method: `ensureDatabaseExists(rootDatabase, sequenceDatabase)`
  - Connects to PostgreSQL using root/admin credentials from config.
  - Validates required sequence DB user/password presence in runtime config.
  - Ensures target sequence role exists (`create role ... login password ...`) or updates password (`alter role ...`).
  - Checks `pg_database` for the target sequence DB using `select exists(...)` (safe when DB is absent).
  - Creates DB on first start when missing with sequence user as DB owner.
  - Grants DB-level (`CONNECT`, `TEMPORARY`) and schema-level (`USAGE`, `CREATE` on `public`) permissions for sequence user.
  - Throws clear `IllegalStateException` when root credentials/permissions are invalid.

### `AppConfig` model
- `sourceDatabase`, `sequenceDatabase`: credentials + schema + db.
- `rootDatabase`: PostgreSQL root/admin connection used at startup to auto-create the sequence database if missing.
- `sourceTable`: source detections table name + optional `loadFrom` timestamp (lower bound for `created_at`).
- `notifications`: telegram on/off + token/chat id.
- `timing`: thresholds for alerts and test-drive reset.
- `reports.outputDirectory`: optional folder path for saving generated XLSX file copy on each report request.
- `cameras`: camera lists for Drive in / Service / Parking logical points plus transition cameras `driveInToService` and `serviceToDriveIn`. Each camera is matched by `analyticsId` and optional direction range.
- `servicePosts`: list where each post has one `analyticsId` and two direction ranges (`inDirectionRange`, `outDirectionRange`) to split `Post In` and `Post Out`; `postName` is preserved into `StageWindow.reportLabel()` so reports can show concrete post numbers/names.
- `DirectionRange.contains(direction)` supports wrap-around intervals that cross `0` degrees (`270 -> 90`) and uses an exclusive upper bound so neighboring ranges can share a border without ambiguous double matches.

### `DetectionService`
- Method: `loadAllDetections()`
  - Builds SQL from runtime config (`schema.table`).
  - If `sourceTable.loadFrom` is configured, adds `where created_at >= ?` to limit data scan window.
  - Reads rows sorted by `created_at, id`.
  - Maps each row to immutable `Detection` record.
- Method: `loadDetectionsBetween(fromInclusive, toExclusive)`
  - Builds SQL with `where created_at >= ? and created_at < ?`.
  - Used by the dated XLSX endpoint so sequence formation is restricted to one requested calendar day.
  - Reads rows sorted by `created_at, id`.

### `SequenceEngine`
- Method: `build(List<Detection>, AppConfig)`
  - Stateless sequence builder.
  - Maps every detection to logical camera type by:
    1. `analyticsId` equality,
    2. direction-range pass.
  - Tracks one active sequence per plate and normalizes same-timestamp detections for the same plate by shifting later events by `+1 second`.
  - Maintains a mutable active-stage timeline where only one stage can be open at a time.
  - Supports repeated stage occurrences (`Drive In`, `Service`, `Post`, `Parking`, `Backyard`, `Test-Drive`) without collapsing them into fixed columns.
  - Applies recovery rules: any next stage closes the previous one at `eventTime - 1 second`; exit-only events create partial stages with empty `In`; `Post In` without active `Service` injects a recovery `Service`; `Service Out` / `Parking Out` detected during an already-open `Backyard` are preserved as additional partial recovery windows instead of being dropped.
  - Applies sticky-post rules per configured post label: while `Post N` is active, repeated `Post N In` is ignored, repeated `Post N Out` only updates an in-memory `outTimeCandidate`, and the `Post` window closes only on another stage or sequence finalization.
  - When sticky `Post` closes without an explicit `Service In`, may emit a synthetic `Service` window between the resolved `Post` end and the next non-service event; open sticky `Post` stages keep `Out = null`.
  - Handles `Backyard` as an explicit stage opened by `Drive-In -> Service`, `Service Out`, or `Parking Out`, with duplicate backyard triggers ignored while `Backyard` is active, yet still allowing exit-only recovery windows to be appended during that active backyard span.
  - Handles `Test-Drive` as a candidate triggered by `Drive-In Out` or `Service -> Drive-In`; `Service -> Drive-In` first closes any active stage at `eventTime - 1 second`, then materializes `Test-Drive` only after the configured silence window and drops it when the absence reaches the configured timeout.
  - Closes/open-rolls sequences after 48 hours without detections for the same plate.
  - Drops transition-only sequences that finished without any concrete stage windows, preventing synthetic `No stages` records from reaching storage/reporting layers.
  - Produces `SequenceRecord` with chronological `StageWindow` entries, per-stage alerts, optional report-label overrides (used for post names/numbers), path steps, and storage/report helpers.

### `SequenceStorageService`
- Method: `initialize()`
  - Ensures `vehicle_sequences` table exists.
  - When PostgreSQL connection cannot be obtained, throws `IllegalStateException` with actionable diagnostics (`host`, `port`, `db`, `user`) for operator troubleshooting.
- Method: `replaceAll(List<SequenceRecord>)`
  - Deletes previous rows.
  - Inserts each sequence with path, finish time, stage durations and joined alerts.

### `AlertJobStorageService`
- Method: `initialize()`
  - Ensures `alert_jobs` table and partial pending-due index exist.
  - Table stores alert queue state (`PENDING`/`SENT`/`CANCELLED`) with timestamps (`trigger_at`, `due_at`, `sent_at`, `cancelled_at`).
- Method: `upsertPending(...)`
  - Idempotently inserts or reactivates a pending alert job using unique key (`plate_number`, `alert_type`, `trigger_at`).
- Method: `cancel(...)`
  - Cancels only pending jobs for a given alert key when stage is completed in time.
- Method: `findDuePending(now, limit)`
  - Returns indexed, due pending jobs ordered by `due_at`.
- Method: `markSent(id, sentAt)`
  - Moves pending jobs to `SENT` after successful dispatch attempt.

### `AlertSchedulerService`
- Scheduled method: `syncPendingJobs()`
  - Runs with fixed delay (`alerts.sync.delay.millis`, default `10000`).
  - Loads detections, rebuilds sequences, and synchronizes DB-backed alert jobs:
    - `DRIVE_IN_OUT_MISSING` (trigger: `startedAt`, due: `startedAt + driveInToDriveOutAlertMinutes`)
    - `SERVICE_POST_IN_MISSING` (trigger: `serviceInAt`, due: `serviceInAt + serviceToPostAlertMinutes`)
  - Cancels pending jobs when expected next stage already exists (`driveInOutAt` / `postInAt`).
- Scheduled method: `dispatchDueAlerts()`
  - Runs with fixed delay (`alerts.dispatch.delay.millis`, default `5000`).
  - Reads due pending jobs in batches, sends Telegram messages, marks jobs as `SENT`.

### `TelegramNotifier`
- Method: `sendIfEnabled(AppConfig.NotificationsConfig, String)`
  - No-op when notifications are missing/disabled.
  - Builds Telegram Bot API request payload and sends POST.
  - Fails silently (exceptions are swallowed).

### `ReportService`
- Method: `buildReport()`
  - Orchestrates:
    1. load detections,
    2. build sequences,
    3. start async storage refresh (`vehicle_sequences`) in background,
    4. generate XLSX report bytes,
    5. optionally persist `sequences.xlsx` into `reports.outputDirectory`,
    6. return HTTP response without waiting for DB refresh completion.
  - Does not dispatch Telegram alerts anymore (alerts are handled by timed background workers).
- Method: `buildReport(reportDate)`
  - Calculates `fromInclusive = reportDate 00:00:00` and `toExclusive = next day 00:00:00`.
  - Loads only detections inside that window.
  - Builds the same XLSX structure, but persists/returns it as `sequences-dd-MM-yyyy.xlsx`.
- Internal method: `toXlsx(...)`
  - Creates two worksheets:
    - `Sequences` with stage-oriented columns: `Stage`, `Time in`, `Time out`, `Duration`, `Alerts`.
    - `Events` with flat stage columns: `Plate`, `Stage`, `In time`, `Out time`, `Duration`, `Alarms`.
  - For `Sequences`: for each `SequenceRecord`, writes a plate marker row (plate in `Time out` column), then writes one row per available stage (`Drive In`, `Service`, configured post name such as `Post 1`, `Service`, `Backyard`, `Parking`) with dynamic inclusion based on available timestamps. Records with zero stage windows are skipped entirely.
  - For `Events`: writes one row per stage occurrence, including repeated `Service` and `Backyard` stages, with the plate repeated on every row; post stages use the preserved `postName` label. Records with zero stage windows are skipped entirely.
  - Formats timestamps as `yyyy-MM-dd HH:mm:ss` and computes duration as `HH:mm:ss`; duration is empty when one of timestamps is missing, except for open `Post` stages where duration uses `SequenceRecord.finishedAt` while `Out time` remains empty.
  - Writes `none` in alerts for the first stage row when the sequence has no alerts.

### `SourcePullTriggerService`
- Method: `triggerPull()`
  - Provides concurrency-safe trigger for reading source detections.
  - Protection rules:
    - only one trigger execution at a time (`RUNNING` status for parallel calls),
    - 30-second cooldown after each successful trigger (`COOLDOWN` status).
  - On successful trigger reads detections via `DetectionService.loadAllDetections()` and returns loaded row count.

### `SourceTriggerController`
- HTTP GET `/source/trigger-pull`.
- Invokes `SourcePullTriggerService.triggerPull()`.
- Response statuses:
  - `200` for `TRIGGERED` (`detectionsLoaded` in body),
  - `429` for `COOLDOWN` (`retryAfterMillis` in body),
  - `409` for `RUNNING`.

### `ReportController`
- HTTP GET `/report/sequences.xlsx`.
  - Returns XLSX attachment produced by `ReportService.buildReport()` with attachment name `sequences.xlsx`.
- HTTP GET `/report/sequences.xlsx/{reportDate}` where `reportDate` format is `dd-MM-yyyy`.
  - Returns XLSX attachment produced by `ReportService.buildReport(reportDate)` with attachment name `sequences-dd-MM-yyyy.xlsx`.
  - Only detections from the requested day window are used to form sequences.

## Data flow

1. `AlertSchedulerService.syncPendingJobs()` periodically rebuilds sequences and synchronizes `alert_jobs` (`PENDING` upsert or cancel on stage completion).
2. `AlertSchedulerService.dispatchDueAlerts()` periodically sends only due pending jobs via `TelegramNotifier` and marks them as `SENT`.
3. Optional trigger request hits `SourceTriggerController` to force source read with cooldown/parallel-call protection.
4. Report request hits `ReportController`.
5. `ReportService` asks `DetectionService` for either all detections or the requested day-bounded detection window.
6. `SequenceEngine` computes sequence records.
7. `ReportService` starts asynchronous `SequenceStorageService` refresh for `vehicle_sequences` (non-blocking for HTTP response).
8. XLSX is built in-memory and returned immediately to caller while DB refresh continues in background.

## Testing

Unit tests are isolated from live infrastructure and cover all services:
- `SequenceEngineTest`: full sequence including Post Out overwrite semantics + direction filtering.
- `DetectionServiceTest`: SQL construction, JDBC row mapping, and explicit date-range filtering for dated reports.
- `SequenceStorageServiceTest`: DDL initialization and replace-all persistence flow.
- `ReportServiceTest`: orchestration, storage refresh, XLSX content, dated filename persistence, and day-bounded report generation.
- `TelegramNotifierTest`: safe no-op behavior for null/disabled notifications.
- `SourcePullTriggerServiceTest`: trigger success, cooldown behavior, and parallel-run protection.
- `AlertSchedulerServiceTest`: pending alert-job sync (upsert/cancel) and due-job dispatch flow.

## Logging

- Application actions are logged to console using SLF4J (startup config, datasource initialization, HTTP requests, source pulls, sequence calculations, persistence operations, report generation and Telegram notifications).
- Trigger endpoint logs cooldown/running/triggered outcomes for concurrent external callback diagnostics.

Integration test (live PostgreSQL):
- `PostgresDatabaseOperationsIntegrationTest`: runs against local PostgreSQL (`localhost:5432`) and verifies real DB operations chain: database/bootstrap role provisioning, detection reads from `videoanalytics.alpr_detections`, sequence table creation, and sequence persistence writes. Test auto-skips when PostgreSQL is unavailable.


### Updated stage processing rules
- Stage timeline: each sequence is a chronological list of stage windows. The same stage may appear many times, but only one stage may be active at a time.
- Time normalization: if two detections for the same plate share the same timestamp, the later one is shifted by `+1 second`.
- Recovery closure: when an event starts the next stage, the previous active stage closes at `eventTime - 1 second`; exit-only recovery stages keep `Out = eventTime` and `In = null`; if `Service Out` or `Parking Out` arrives while `Backyard` is already active, the recovery stage is appended without closing that active backyard window.
- `Drive In`: `Drive-In In` opens the stage, `Drive-In Out` closes it, and any later stage can also recover-close it.
- `Service` / `Post`: `Service In` opens `Service`; `Post In` closes `Service` and opens sticky `Post`; duplicate `Post In` on the same configured post is ignored; `Post Out` on the same post only updates `outTimeCandidate`; another stage (including another post) closes sticky `Post` using `outTimeCandidate` or `nextEvent - 1 second`; `Post In` without active `Service` injects a partial `Service` ending at `Post In - 1 second`.
- Synthetic service after sticky `Post`: when sticky `Post` closes and an explicit `Service In` is missing, `SequenceEngine` may create a synthetic `Service` from `resolvedPostOut + 1 second` until `Service Out` or `nextEvent - 1 second`; if sticky `Post` never closes before sequence finalization, it remains open with `Out = null`.
- `Parking`: `Parking In` opens `Parking`; `Parking Out` closes it and starts `Backyard` instead of closing the sequence; if `Parking Out` arrives while `Backyard` is already active, it is retained as a partial `Parking` recovery row.
- `Backyard`: opened by `Drive-In -> Service`, `Service Out`, or `Parking Out`; ends on the next stage event; repeated backyard triggers while active are ignored, but exit-only recovery rows may still coexist chronologically inside the same backyard span.
- `Test-Drive`: candidate on `Drive-In Out` / `Service -> Drive-In`; `Service -> Drive-In` first closes the active stage so no overlap remains; candidate is cancelled by an early next event; materialized only after the configured quiet window; removed when the vehicle absence reaches the timeout.
- Alerts: computed per stage and only for non-partial `Drive In` and `Service` stages. A stage keeps its due alert if it stayed open past the threshold even if it eventually closed later.
