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
- SQL reads detections sorted by `created_at, id` from configured source table.
- Projects rows to `Detection(id, plateNumber, analyticsId, direction, createdAt)`.

### `SequenceEngine`
- Stateless sequence builder.
- Maps every detection to logical camera type by:
  1. `analyticsId` equality,
  2. direction-range pass.
- Tracks active sequence per plate.
- Handles sequence lifecycle:
  - start on Drive in (in) / Service-Drive in (in),
  - append stage events (service, post, parking),
  - close on parking out,
  - reset as new sequence if test-drive gap exceeds configured reset threshold.
- Produces `SequenceRecord` with path, stage durations, alerts.

### `SequenceStorageService`
- Ensures `vehicle_sequences` table exists in sequence DB.
- Replaces current rows with recomputed rows.

### `TelegramNotifier`
- Sends alerts to Telegram Bot API only if `notifications.enabled=true`.

### `ReportService`
- Orchestrates flow:
  1. load detections,
  2. build sequences,
  3. persist sequences,
  4. send alerts,
  5. create XLSX binary.

### `ReportController`
- HTTP GET `/report/sequences.xlsx`.
- Returns XLSX attachment.

## Data flow

1. HTTP request hits `ReportController`.
2. `ReportService` loads source detections and computes sequences.
3. Sequence DB updated in `vehicle_sequences`.
4. XLSX generated in-memory and returned.

## Testing

- `SequenceEngineTest` validates:
  - full sequence construction,
  - direction filtering behavior.
- Tests are unit-only and do not call live DB/Telegram.
