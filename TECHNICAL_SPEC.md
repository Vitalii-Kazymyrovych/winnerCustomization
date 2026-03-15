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
- `servicePosts`: list of post camera pairs (`in`, `out`).

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
    - start on Drive in (in) / Service-Drive in (in),
    - append stage events (service, post, parking),
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
    4. send unique alert notifications,
    5. generate XLSX report bytes.
- Internal method: `toXlsx(...)`
  - Creates `Sequences` worksheet with columns: Plate, Start, Finish, Path, Stage durations, Alerts.

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

1. Optional trigger request hits `SourceTriggerController` to force source read with cooldown/parallel-call protection.
2. Report request hits `ReportController`.
3. `ReportService` asks `DetectionService` for detections.
4. `SequenceEngine` computes sequence records.
5. `SequenceStorageService` refreshes `vehicle_sequences` table.
6. `TelegramNotifier` sends deduplicated alerts.
7. XLSX is built in-memory and returned.

## Testing

Unit tests are isolated from live infrastructure and cover all services:
- `SequenceEngineTest`: full sequence + direction filtering.
- `DetectionServiceTest`: SQL construction and JDBC row mapping.
- `SequenceStorageServiceTest`: DDL initialization and replace-all persistence flow.
- `ReportServiceTest`: orchestration, storage refresh, telegram notification triggering, XLSX content.
- `TelegramNotifierTest`: safe no-op behavior for null/disabled notifications.
- `SourcePullTriggerServiceTest`: trigger success, cooldown behavior, and parallel-run protection.

## Logging

- Application actions are logged to console using SLF4J (startup config, datasource initialization, HTTP requests, source pulls, sequence calculations, persistence operations, report generation and Telegram notifications).
- Trigger endpoint logs cooldown/running/triggered outcomes for concurrent external callback diagnostics.

Integration test (live PostgreSQL):
- `PostgresDatabaseOperationsIntegrationTest`: runs against local PostgreSQL (`localhost:5432`) and verifies real DB operations chain: database/bootstrap role provisioning, detection reads from `videoanalytics.alpr_detections`, sequence table creation, and sequence persistence writes. Test auto-skips when PostgreSQL is unavailable.
