# Technical Specification

## Architecture Overview
Application type: Spring Boot monolith with programmatic JDBC data sources (no Spring Data/JPA).

### Runtime flow
1. `WinnerCustomizationApplication` starts.
2. `ConfigLoader` loads `config.json`.
3. `DatabaseBootstrapServiceImpl` ensures target DB/schema/user/tables/privileges.
4. `SourceRepository` and `TargetRepository` initialize JDBC connections.
5. `SchedulerServiceImpl.performInitialLoad()` processes full source history.
6. `SchedulerServiceImpl.startPolling()` periodically fetches new detections and updates only active state in target DB.
7. `ReportController` serves XLSX reports from `ReportServiceImpl`.

## Packages and Components

### `config`
- `AppConfig`: root configuration object.
- `DatabaseConfig`: DB host/port/root/source/target settings and JDBC URL builder.
- `DbConnectionConfig`: per-connection DB/schema/user/password tuple.
- `WorkflowConfig`: lists of real/transitional/single-camera stage configs.
- `RealStageConfig`, `TransitionalStageConfig`, `SingleCameraConfig`: stage definitions.
- `TriggerConfig`: trigger definition (`in/out`, `analyticsId`, optional direction).
- `AlertConfig`, `AlertTriggerConfig`: alert rule definition.
- `MessagingConfig`: toggle + Telegram credentials.
- `ConfigLoader`: loads `config.json` at startup and exposes parsed config.

### `model`
- `Detection`: source row model from `videoanalytics.alpr_detections`.
- `PlateSequence`: aggregate root for one plate's stage timeline.
- `Stage`: sequence stage state (active/full/candidate, in/out times, duration).
- `AlertRecord`: computed alert state linked to stages.

### `repository`
- `SourceRepository`
  - `initialize()`
  - `fetchAllDetections()`
  - `fetchDetectionsAfter(LocalDateTime)`
- `TargetRepository`
  - `initialize()`
  - `rewriteAll(List<PlateSequence>)` — startup full rewrite; clears and rebuilds all three tables.
  - `updateActive(List<PlateSequence>)` — polling incremental write; deletes only rows where `active=true` and reinserts the current active state. Closed sequences, inactive stages, and inactive alerts are not touched.
  - Tracks `nextSeqId/nextStageId/nextAlertId` to assign new IDs without colliding with existing closed-sequence rows.

### `service.logic`
- Interfaces:
  - `DatabaseBootstrapService`
  - `SchedulerService`
  - `SequenceEngineService`
- Implementations:
  - `DatabaseBootstrapServiceImpl`
    - `bootstrap()`
    - `createTables(...)`
  - `SchedulerServiceImpl`
    - `performInitialLoad()`
    - `startPolling()`
    - `stopPolling()`
    - periodic `pollAndProcess()`
  - `SequenceEngineServiceImpl`
    - detection processing, stage transitions, timeout maintenance, alert activation/deactivation, sequence close logic
    - `getActiveSequences()`: returns currently active sequences (used by polling write)
    - `getAllSequences()`: returns all sequences including closed (used by startup write and reports)
  - `TriggerMatcher`: resolves incoming detections to configured trigger candidates.
  - `DirectionMatcher`: direction matching helper.

### `service.reports`
- Interface `ReportService`
  - `generateFullReport()`
  - `generateDayReport(LocalDate)`
- `ReportServiceImpl`
  - creates workbook with 3 sheets:
    - `Sequences (Active)`
    - `Sequences (Closed)`
    - `Events`
  - writes to `reportsDir`.

### `alerts`
- `AlertService` interface
- `AlertServiceImpl`: dispatch policy and messaging integration.
- `TelegramSender`: Telegram transport for enabled messaging mode.

### `controller`
- `ReportController`
  - `GET /report/sequences.xlsx`
  - `GET /report/sequences.xlsx/{dd-MM-yyyy}`

### `dto` and `mapper`
- DTOs: `SequenceDto`, `StageDto`, `AlertDto`.
- `ModelMapper`: maps domain models to DTOs for external responses.

## Database Contracts

### Source DB
- Schema/table: `videoanalytics.alpr_detections`
- Required columns consumed include:
  - `id`, `plate_number`, `analytics_id`, `created_at`, `direction`, etc.

### Target DB
- Schema configured in `database.sequence.schema` (default: `alpr_sequences`).
- Tables:
  - `sequences`
  - `stages` (column `"full"` is quoted due SQL keyword conflict)
  - `alerts`

## Testing
- Unit tests: `./mvnw -B test`
- Current tests focus on logic matchers and sequence engine behavior:
  - `SequenceEngineTest`
  - `TriggerMatcherTest`
  - `DirectionMatcherTest`
