# Technical specification

## Components

### `JacksonConfig`
- Provides explicit `ObjectMapper` bean for JSON serialization/deserialization dependencies.
- Uses `findAndRegisterModules()` and relies on `jackson-datatype-jsr310` on classpath, so runtime config can deserialize Java time values (`LocalDateTime` such as `sourceTable.loadFrom`).
- Guarantees `RuntimeConfig` constructor injection works during application startup.

### `RuntimeConfig`
- Loads runtime JSON configuration from `<working_dir>/config.json` at startup.
- Stores the active config in an `AtomicReference<AppConfig>` so controllers/services always read the latest committed version.
- Enriches legacy configs with a generated `workflow` section through `WorkflowDefaultsFactory`, validates workflow references/timeouts, and can persist updated config back into `config.json` without restart.

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
- `workflow`: expanded runtime workflow model with `defaultSequenceCloseTimeoutMinutes` and `stages[]`. Each stage supports `name`, `labelTemplate`, `startTriggers`, `finishTriggers`, candidate/sticky timeout settings, duplicate policies, transition references, and per-trigger notification metadata.
- `cameras`: legacy camera lists for Drive in / Service / Parking logical points plus transition cameras `driveInToService` and `serviceToDriveIn`. Each camera is matched by `analyticsId` and optional direction range. They are still supported and are converted into a default `workflow` model when `workflow` is omitted from JSON.
- `servicePosts`: list where each post has one `analyticsId` and two direction ranges (`inDirectionRange`, `outDirectionRange`) to split `Post In` and `Post Out`; `postName` is preserved into `StageWindow.reportLabel()` so reports can show concrete post numbers/names.
- `DirectionRange.contains(direction)` supports wrap-around intervals that cross `0` degrees (`270 -> 90`) and uses an exclusive upper bound so neighboring ranges can share a border without ambiguous double matches.

### `WorkflowDefaultsFactory`
- Method: `enrich(AppConfig)`
  - Returns config unchanged when `workflow.stages` already exists.
  - Otherwise synthesizes a workflow model from legacy `cameras` and `timing` sections so the UI/API can expose a declarative stage configuration without breaking older `config.json` files.
- Generates stage definitions for `drive_in`, `service`, each configured post, `parking`, `backyard`, and `test_drive`, including labels, trigger metadata, and alert timing defaults.

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
  - Stateless workflow-driven sequence builder.
  - Compiles `workflow.stages[]` into in-memory stage/trigger definitions (`WorkflowDefinition`, `StageDefinition`, `TriggerDefinition`) and resolves detections by `cameraId + directionRange` instead of a fixed `CameraType` switch.
  - Tracks one active sequence per plate and normalizes same-timestamp detections for the same plate by shifting later events by `+1 second`.
  - Uses a state/strategy-style flow internally:
    - trigger resolution chooses a start/finish strategy from config,
    - pending candidate/sticky timeout processing runs before each event,
    - start/finish handlers apply configurable transition policies.
  - Supports arbitrary config-defined stage names and labels (`service_primary`, `post_3`, `parking_secondary`, etc.), so the report/storage model is no longer tied to a hardcoded stage enum.
  - Honors `startMode`, `candidateTimeoutMinutes`, `candidateCloseTimeoutMinutes`, `candidateCancelOnEvents`, `finishMode`, `stickyCloseTimeoutMinutes`, `allowedNextStages`, `unexpectedNextStagePolicy`, `timeoutTransitionToStage`, `intermediateStageOnTransition`, `allowPartialFromFinish`, `startDuplicatePolicy`, `finishDuplicatePolicy`, `sameStageReopenAfterMinutes`, and per-stage `sequenceCloseTimeoutMinutes`.
  - Materializes timeout-driven transitional stages via `timeoutTransitionToStage` and explicit transition insertions via `intermediateStageOnTransition`.
  - Writes `StageWindow` entries with dynamic `stageName`, rendered `reportLabel`, `sticky`/`transitional` flags, `saveAfterSequenceClosed`, and chronological ordering metadata.
  - Evaluates alerts from trigger-level notification configuration instead of hardcoding them to a fixed stage enum.
  - Drops transition-only sequences that finished without any concrete stage windows, preventing synthetic `No stages` records from reaching storage/reporting layers.

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

### `ConfigController`
- HTTP GET `/config`
  - Returns effective runtime config as JSON when JSON is requested.
  - Returns a primitive HTML page with a `<textarea>` editor and POST form when HTML is requested.
- HTTP POST `/config`
  - Accepts either raw JSON or form-urlencoded `json` payload.
  - Validates and persists the config through `RuntimeConfig.save(...)`, then updates the in-memory runtime config immediately.

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
  - For `Sequences`: for each `SequenceRecord`, writes a plate marker row (plate in `Time out` column), then writes one row per available stage (`Drive In`, `Service`, configured post name such as `Post 1`, `Service`, `Backyard`, `Parking`) with dynamic inclusion based on available timestamps. After the final stage row of every non-empty closed sequence, appends `Sequence Closed` with `finishedAt` in the `Time out` column. Records with zero stage windows are skipped entirely.
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
- Stage timeline: each sequence is a chronological list of `StageWindow` entries. Any config-defined stage may appear multiple times, but only one stage can be active at a time.
- Trigger resolution: detections are matched against workflow triggers in config order. Matching uses `cameraId` plus optional `DirectionRange` with wrap-around support and exclusive upper bound.
- Candidate flow: `startMode=candidate` creates `PendingCandidate`; it materializes only after `candidateTimeoutMinutes`, may be refreshed by duplicate policy, and is cancelled by configured `candidateCancelOnEvents` or by sequence timeout.
- Sticky flow: `finishMode=sticky` stores `pendingStickyOutAt`; later real stages or sticky timeout close the stage and may insert `timeoutTransitionToStage` windows.
- Unexpected transitions: `allowedNextStages` + `unexpectedNextStagePolicy` decide whether the engine ignores an event, starts a partial stage, inserts an intermediate transitional stage, or closes current and opens next.
- Partial stages: any stage with `allowPartialFromFinish=true` can generate a partial row when only its finish trigger is observed.
- Validation/runtime safety: `RuntimeConfig` validates workflow references plus supported values for start/finish modes and duplicate/unexpected-transition policies before saving live config updates.
