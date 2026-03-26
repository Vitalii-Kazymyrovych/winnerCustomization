# Technical Specification (Implementation)

## Package structure

- `config`
  - `ConfigLoader`: reads strict JSON from `config.json` and exposes `AppConfig` bean.
  - `AppConfig`, `DatabaseConfig`, `DbConnectionConfig`, `WorkflowConfig`, `StageRuleConfig`, `TriggerConfig`, `AlertRuleConfig`, `MessagingConfig`: typed config model.
- `model`
  - `Detection`: source ALPR event.
  - `Sequence`: per-plate aggregate with list of `Stage`.
  - `Stage`: stage instance with in/out/duration, flags and alert messages.
  - `Alert`: alert countdown state.
  - `StageType`: `REAL`, `TRANSITIONAL`, `SINGLE_CAMERA`.
- `repository`
  - `SourceDetectionRepository`: abstraction for source reads.
  - `SqlFileSourceDetectionRepository`: development/local implementation parsing `alpr_detections.sql`.
  - `SequenceStateRepository`: abstraction for target state writes/reads.
  - `InMemorySequenceStateRepository`: current write/read cache.
- `service.logic`
  - `SequenceEngineService`: main rebuild API.
  - `SequenceEngineServiceImpl`: deterministic engine that builds stages and alerts from sorted detections.
  - `EngineOrchestratorService`: startup + scheduled rebuild orchestration and persistence to target repository.
- `alerts`
  - `AlertSender`: abstraction for outbound alerts.
  - `TelegramAlertSender`: logs alerts when disabled, sends Telegram Bot API requests when enabled.
- `service.reports`
  - `ReportService`: report generation contract.
  - `ReportServiceImpl`: builds XLSX workbook with active/closed/events sheets.
- `controller`
  - `ReportController`: XLSX download endpoints.
  - `SequenceController`: read API for current sequence state.
- `dto`, `mapper`, `util`
  - `SequenceDto`, `StageDto`, `SequenceMapper`, `DurationFormatter`.
- `bootstrap`
  - `TargetDatabaseBootstrapService`: startup bootstrap log hook for target DB configuration.

## Main interactions

1. Spring starts and loads `config.json` through `ConfigLoader`.
2. `TargetDatabaseBootstrapService` validates/logs target DB setup inputs.
3. `EngineOrchestratorService` runs full rebuild:
   - gets detections from `SourceDetectionRepository`
   - passes detections to `SequenceEngineServiceImpl`
   - stores resulting sequences + alerts in `SequenceStateRepository`
4. Scheduled rebuild repeats with configured interval.
5. REST controllers read the current in-memory target state to return JSON/XLSX outputs.

## Sequence engine behavior implemented

- UTC-based processing.
- Circular direction matching with 90° tolerance.
- Real stage in/out handling with dedup and partial open on out-first event.
- Single-camera stage open/update.
- Active stage closure when next stage opens.
- Global sequence timeout closure.
- Alert creation, timeout decrement and send/invalidate logic.
- Duration recalculation for active and closed stages.

## Notes

- Current target/source persistence is in-memory + SQL-file source adapter for deterministic local development.
- Repository interfaces are ready for PostgreSQL-backed implementations.
