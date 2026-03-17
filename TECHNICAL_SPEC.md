# Technical specification

## Components

### `JacksonConfig`
- Provides explicit `ObjectMapper` bean for JSON serialization/deserialization dependencies.
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
- `sourceTable`: source detections table name.
- `notifications`: telegram on/off + token/chat id.
- `timing`: thresholds for alerts and test-drive reset.
- `cameras`: all logical camera slots, each with `analyticsId` and optional direction range.
- `servicePosts`: list of post cameras (`in`).

### `DetectionService`
- Method: `loadAllDetections()`
  - Builds SQL from runtime config (`schema.table`).
  - Reads rows sorted by `created_at, id`.
  - Maps each row to immutable `Detection` record.

### `SequenceEngine`
- Method: `build(List<Detection>, AppConfig)`
  - Stateless sequence builder.
  - Maps every detection to logical camera type by:
    1. `analyticsId` equality,
    2. direction-range pass.
  - Tracks active sequence per plate.
  - Handles lifecycle:
    - start on Drive in (in) / Service (in),
    - append stage events (service, post, parking),
    - treat `Service (in) -> Post 1 (in)` as service stage,
    - treat `Post 1 (in) -> Service (out)` as post stage (no post-out camera),
    - close on parking out,
    - reset as new sequence if test-drive gap exceeds configured reset threshold.
  - Produces `SequenceRecord` with path, stage durations, alerts.

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
    3. initialize/replace storage,
    4. generate XLSX report bytes.
  - Does not dispatch Telegram alerts anymore (alerts are handled by timed background workers).
- Internal method: `toXlsx(...)`
  - Creates `Sequences` worksheet with stage-oriented columns: `Stage`, `Time in`, `Time out`, `Duration`, `Alerts`.
  - For each `SequenceRecord`, writes a plate marker row (plate in `Time out` column), then writes one row per available stage (`Drive in`, `Service`, `Post`, `Parking`) with dynamic inclusion based on available timestamps. Service stage ends at `postInAt` when present (fallback: `serviceOutAt`), Post stage starts at post-in and ends at service-out.
  - Computes `Duration` from stage start/end as `HH:mm:ss`; empty when one of timestamps is missing.
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
- Returns XLSX attachment produced by `ReportService`.

## Data flow

1. `AlertSchedulerService.syncPendingJobs()` periodically rebuilds sequences and synchronizes `alert_jobs` (`PENDING` upsert or cancel on stage completion).
2. `AlertSchedulerService.dispatchDueAlerts()` periodically sends only due pending jobs via `TelegramNotifier` and marks them as `SENT`.
3. Optional trigger request hits `SourceTriggerController` to force source read with cooldown/parallel-call protection.
4. Report request hits `ReportController`.
5. `ReportService` asks `DetectionService` for detections.
6. `SequenceEngine` computes sequence records.
7. `SequenceStorageService` refreshes `vehicle_sequences` table.
8. XLSX is built in-memory and returned.

## Testing

Unit tests are isolated from live infrastructure and cover all services:
- `SequenceEngineTest`: full sequence + direction filtering.
- `DetectionServiceTest`: SQL construction and JDBC row mapping.
- `SequenceStorageServiceTest`: DDL initialization and replace-all persistence flow.
- `ReportServiceTest`: orchestration, storage refresh, XLSX content.
- `TelegramNotifierTest`: safe no-op behavior for null/disabled notifications.
- `SourcePullTriggerServiceTest`: trigger success, cooldown behavior, and parallel-run protection.
- `AlertSchedulerServiceTest`: pending alert-job sync (upsert/cancel) and due-job dispatch flow.

## Logging

- Application actions are logged to console using SLF4J (startup config, datasource initialization, HTTP requests, source pulls, sequence calculations, persistence operations, report generation and Telegram notifications).
- Trigger endpoint logs cooldown/running/triggered outcomes for concurrent external callback diagnostics.

Integration test (live PostgreSQL):
- `PostgresDatabaseOperationsIntegrationTest`: runs against local PostgreSQL (`localhost:5432`) and verifies real DB operations chain: database/bootstrap role provisioning, detection reads from `videoanalytics.alpr_detections`, sequence table creation, and sequence persistence writes. Test auto-skips when PostgreSQL is unavailable.
