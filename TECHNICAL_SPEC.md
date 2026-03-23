# Technical specification

## Architecture overview
The application is split into configuration, repositories, domain services, and web controllers.

### Configuration
- `AppConfig` contains infrastructure settings (source/sequence/root database, source table, report output, Telegram delivery) and the sequence-engine specification.
- `RuntimeConfig` loads `config.json`, validates stage uniqueness, timeout positivity, and direction ranges, then keeps the active config in memory.

## Domain model
- `Detection` is the raw source event (`id`, `plateNumber`, `analyticsId`, `direction`, `createdAt`).
- `SequenceRecord` represents one plate sequence with `startedAt`, optional `finishedAt`, `closed`, ordered `StageWindow`s, and generated notification events.
- `StageWindow` stores `stageName`, `stageLabel`, `stageType`, `partial`, `candidate`, `timeIn`, `timeOut`, `lastSeenAt`, and attached alerts.
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
The engine is built around the three stage types only.

#### Real stages
- Open on the first matching `inTrigger`.
- Matching `outTrigger` updates sticky `Out` time.
- If another real or single-camera stage starts, the current stage is logically closed while preserving its sticky `Out` when one already exists.
- If an `Out` for another real stage arrives while something else is active, a partial real stage is created with empty `In`.
- A sequence can now still be considered closed when the last active real stage already has a sticky `Out` timestamp.

#### Transitional stages
- A candidate is created either from `triggerCameras` or immediately after closing an allowed previous stage.
- Closing a `real` stage via its `Out` trigger now immediately seeds those `allowedAfter` candidates too, so transitions such as `Parking -> Backyard` do not depend on a second explicit Backyard-camera event.
- Camera-triggered transitional candidates are accepted only when the active stage (or the latest non-partial recorded stage after a same-event closure) matches `allowedAfter`; otherwise the trigger is ignored as an impossible standalone transition.
- Repeated detections for the same transitional source refresh the candidate timeout instead of creating duplicate stages.
- A candidate materializes only after `candidateTimeoutSeconds` without another stage start, and repeated detections from the same source refresh that deadline instead of spawning duplicate transitional rows.
- Once a transitional candidate has materialized, the resulting stage is always reportable; `showInReportIfIncomplete = false` only removes it later if the sequence ends on that transitional timeout without any following concrete stage.
- If the same transitional stage is already active, repeated trigger-camera detections only refresh internal activity and do not create a second consecutive stage.
- If `sequenceCloseTimeoutOverrideSeconds` is set (including `0`), the materialized transitional stage owns the sequence inactivity timeout; `0` closes the stage/sequence immediately after materialization time, which is used for Backyard-like terminal transitions.
- As soon as a later `real` or `single_camera` stage starts, that transitional timeout override is cleared so the newly opened concrete stage falls back to the normal sequence timeout rules.

#### Single-camera stages
- First detection opens the stage with `In = detection time` and empty `Out`.
- Repeated detections on the same camera refresh `lastSeenAt` only.
- Repeated detections for the same single-camera stage refresh `lastSeenAt` until the configured timeout expires; after that timeout, the next detection starts a brand-new stage row for the same camera.
- A matching real-stage `Out` event creates a partial real recovery row but no longer destroys the active single-camera context unless the new concrete stage boundary actually takes over the timeline.
- If no later boundary arrives, the stage closes at `lastSeenAt` once `timeoutSeconds` has elapsed; if the sequence itself closes first, the row is persisted as incomplete with empty `Out`.

#### Shared rules
- Duplicate detections with the same camera/direction inside `duplicateSuppressionSeconds` are ignored.
- Timestamps are normalized per plate so events remain strictly increasing.
- Sequence closure happens after `sequenceCloseTimeoutMinutes` of inactivity unless an open stage or pending candidate still keeps the sequence active.

### `NotificationService`
- Evaluates camera-based notification rules against detections.
- Builds pending notification jobs for repository persistence.
- Dispatches due notifications through `TelegramNotifier`.
- Deduplicates identical `(plate, triggeredAt, message)` notification events before report enrichment.
- Attaches produced messages back to overlapping `StageWindow`s in reports, but only when the notification plate matches the sequence plate.

### `ReportService`
- Loads detections through `DetectionService`.
- Builds sequences with `SequenceEngine` using the actual report-generation timestamp.
- Enriches stages with notification alerts.
- Persists sequences asynchronously through `SequenceStorageService`.
- Writes two XLSX sheets:
  - `Sequences` — grouped by plate, with the plate marker row and the `Sequence closed` marker centered in the `Out time` column.
  - `Events` — flat stage list.
- Omits the old technical `Type` column from both sheets.
- Computes open-stage duration as `reportGeneratedAt - timeIn` while leaving `Out time` empty.

### Supporting services
- `DetectionService` proxies repository reads.
- `SequenceStorageService` initializes and replaces stored sequence snapshots.
- `AlertSchedulerService` periodically syncs pending notifications from detections and dispatches due jobs.
- `TelegramNotifier` sends Telegram messages when messaging is enabled.
- `DatabaseBootstrapService` prepares configured databases/schemas.
- `WorkflowDefaultsFactory` remains available for default config generation support.

## Web/API layer
- `ReportController` exposes XLSX download endpoints.
- `ConfigController` exposes JSON and HTML config inspection/editing plus `/config/help`.
- `SourceTriggerController` triggers manual source pulls.

## Test coverage focus
Unit tests cover:
- sequence building for real/transitional/single-camera flows,
- repeated single-camera aggregation,
- duplicate transitional suppression,
- report layout and open-stage duration rendering,
- notification cancellation/triggering/deduplication,
- runtime config validation,
- committed `results/` dataset regression coverage for compact sticky-post reporting on production-like data,
- exact CSV snapshot coverage against `results/expected_sequences_logic.csv`, with line-ending normalization so CRLF/LF checkouts compare identically across platforms,
- full-dataset invariants that iterate through every plate / sequence and verify single-camera stage compaction plus non-overlapping stage order,
- controller/JDBC/bootstrap/startup coverage for HTTP adapters, repository SQL generation, runtime-config file persistence, manual source-pull cooldown behavior, and database bootstrap permission flows,
- Java runtime enforcement in Maven for `[21,22)` so the declared project version and test/JaCoCo toolchain stay aligned,
- JaCoCo report generation during `./mvnw -B test` for post-run inspection of instruction/branch coverage.
