# Technical specification

## Components

### `RuntimeConfig`
- Loads runtime JSON configuration from `<working_dir>/config.json` at startup.
- Provides typed `AppConfig` object for all services.

### `JdbcConfig`
- Creates two PostgreSQL data sources and JDBC templates:
  - `sourceJdbc`: reads detections.
  - `sequenceJdbc`: stores computed sequences.

### `AppConfig` model
- `sourceDatabase`, `sequenceDatabase`: credentials + schema + db.
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

### `ReportController`
- HTTP GET `/report/sequences.xlsx`.
- Returns XLSX attachment produced by `ReportService`.

## Data flow

1. Request hits `ReportController`.
2. `ReportService` asks `DetectionService` for detections.
3. `SequenceEngine` computes sequence records.
4. `SequenceStorageService` refreshes `vehicle_sequences` table.
5. `TelegramNotifier` sends deduplicated alerts.
6. XLSX is built in-memory and returned.

## Testing

Unit tests are isolated from live infrastructure and cover all services:
- `SequenceEngineTest`: full sequence + direction filtering.
- `DetectionServiceTest`: SQL construction and JDBC row mapping.
- `SequenceStorageServiceTest`: DDL initialization and replace-all persistence flow.
- `ReportServiceTest`: orchestration, storage refresh, telegram notification triggering, XLSX content.
- `TelegramNotifierTest`: safe no-op behavior for null/disabled notifications.
