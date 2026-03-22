# Technical specification

## Architecture overview
The application is split into configuration, repositories, domain services, and web controllers.

### Configuration
- `AppConfig` contains infrastructure settings (source/sequence/root database, source table, report output, Telegram delivery) and the new sequence-engine specification.
- `RuntimeConfig` loads `config.json`, validates stage uniqueness, timeout positivity, and direction ranges, then keeps the active config in memory.

### Domain model
- `Detection` is the raw source event (`id`, `plateNumber`, `analyticsId`, `direction`, `createdAt`).
- `SequenceRecord` represents one plate sequence with `startedAt`, `finishedAt`, ordered `StageWindow`s, and generated notification events.
- `StageWindow` stores `stageName`, `stageLabel`, `stageType`, `partial`, `candidate`, `timeIn`, `timeOut`, and attached alerts.
- `StageType` is one of `REAL`, `TRANSITIONAL`, `SINGLE_CAMERA`.

## Repository layer
The repository pattern isolates SQL from business logic.

### Interfaces
- `Repository<T>` — generic initialize/read/replace contract.
- `DetectionRepository` — detection loading for all rows or a date interval.
- `SequenceRepository` — persistence for built sequences.
- `NotificationRepository` — persistence for pending notifications and dispatch state.

### JDBC implementations
- `JdbcDetectionRepository` reads from the configured source schema/table.
- `JdbcSequenceRepository` stores sequence headers in `sequence_records` and stage rows in `sequence_stages`.
- `JdbcNotificationRepository` stores pending jobs in `pending_notifications` and marks them as sent.

## Services

### `SequenceEngine`
The engine was rewritten around the new stage types only.

#### Real stages
- Open on the first matching `inTrigger`.
- Matching `outTrigger` updates sticky `Out` time.
- If another real stage starts, the current stage is logically closed while preserving its sticky `Out`.
- If an `Out` for another real stage arrives while something else is active, a partial real stage is created with empty `In`.

#### Transitional stages
- A candidate is created either from `triggerCameras` or immediately after closing an allowed previous stage.
- A candidate materializes only after `candidateTimeoutSeconds` without another stage start.
- Once materialized, it becomes an active transitional stage until the next stage start or sequence finalization.
- If `sequenceCloseTimeoutOverrideSeconds > 0`, the materialized transitional stage forces a shorter close timeout and is removed from the report when the sequence ends by that override.

#### Single-camera stages
- First detection opens the stage with both `In` and `Out` initialized to the detection time.
- Repeated detections on the same camera refresh `Out`.
- If the gap exceeds `timeoutSeconds`, the current stage closes at the last detection and the next detection starts a new stage.

#### Shared rules
- Duplicate detections with the same camera/direction inside `duplicateSuppressionSeconds` are ignored.
- Timestamps are normalized per plate so events remain strictly increasing.
- Sequence closure happens after `sequenceCloseTimeoutMinutes` of inactivity unless a transitional override is active.

### `NotificationService`
- Evaluates camera-based notification rules against detections.
- Builds pending notification jobs for repository persistence.
- Dispatches due notifications through `TelegramNotifier`.
- Attaches produced messages back to overlapping `StageWindow`s in reports.

### `ReportService`
- Loads detections through `DetectionService`.
- Builds sequences with `SequenceEngine`.
- Enriches stages with notification alerts.
- Persists sequences asynchronously through `SequenceStorageService`.
- Writes two XLSX sheets:
  - `Sequences` — grouped by plate.
  - `Events` — flat stage list with stage type and alerts.

### Other services
- `DetectionService` is a thin domain wrapper over `DetectionRepository`.
- `SequenceStorageService` is a thin wrapper over `SequenceRepository`.
- `AlertSchedulerService` periodically syncs pending notifications from detections and dispatches due jobs.
- `SourcePullTriggerService` triggers source loading manually with a cooldown guard.
- `DatabaseBootstrapService` ensures the sequence database and role exist before JDBC beans are used.

## Web layer
- `ReportController` exposes XLSX downloads.
- `ConfigController` exposes JSON/HTML config views and updates.
- `SourceTriggerController` exposes manual source pull.

## Test coverage
Unit tests cover:
- sequence rules for real/transitional/single-camera flows,
- notification cancellation/triggering,
- report XLSX generation,
- runtime config validation,
- config help output.
