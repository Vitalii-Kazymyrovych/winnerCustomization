# Technical Specification (Implementation)

## Package structure

- `config`
  - `ConfigLoader`: strict Jackson loader for external `config.json` (next to jar).
  - `AppConfig`, `DatabaseConfig`, `DbConnectionConfig`, `WorkflowConfig`, `StageRuleConfig`, `TriggerConfig`, `AlertRuleConfig`, `MessagingConfig`: typed configuration model.
- `model`
  - `Detection`: source ALPR event (`id`, `plate`, `analyticsId`, `direction`, `createdAtUtc`).
  - `Sequence`: aggregate keyed by plate (`closed`, `lastDetection`, `closedAtUtc`, stages).
  - `Stage`: stage instance (`in/out`, `lastDetectionTime`, `duration`, `active/full`, `timeoutSeconds`, alerts, close timeout override).
  - `Alert`: alert state (`triggerAnalyticsId`, timeout, message, `createdAtUtc`, active flag).
  - `StageType`: `REAL`, `TRANSITIONAL`, `SINGLE_CAMERA`.
- `repository`
  - `SourceDetectionRepository`: source read abstraction (`findAll`, `findNewerThan`).
  - `SqlFileSourceDetectionRepository`: SQL-file parser adapter (`alpr_detections.sql`) with incremental filter support.
  - `SequenceStateRepository`: target runtime state abstraction.
  - `InMemorySequenceStateRepository`: in-memory state cache used by controllers/reports.
- `service.logic`
  - `SequenceEngineService`: engine contract (`rebuild`, `applyIncremental`).
  - `SequenceEngineServiceImpl`: deterministic sequencing engine with:
    - circular direction matching;
    - in/out and partial/full stage policies;
    - single-camera dedup + out overwrite;
    - transitional `allowedAfter` candidate creation/countdown;
    - sequence close timeout and transitional override timeout;
    - alert creation/cancel/suppression and elapsed-time firing.
  - `EngineOrchestratorService`: startup full rebuild + scheduled incremental polling with `lastProcessedTimestamp` and poll timestamps.
- `alerts`
  - `AlertSender`: outbound notification abstraction.
  - `TelegramAlertSender`: Telegram sender or console fallback when disabled.
- `service.reports`
  - `ReportService`: report contract.
  - `ReportServiceImpl`: XLSX builder with active/closed/events sheets, header rows, UTC day filtering, and report persistence to `reportsDir`.
- `controller`
  - `SequenceController`: `GET /api/sequences`.
  - `ReportController`: XLSX download endpoints.
- `bootstrap`
  - `TargetDatabaseBootstrapService`: PostgreSQL bootstrap (role/database/schema/table ensure + grants, best-effort with warning on unavailable DB).
- `dto`, `mapper`, `util`
  - DTO + mapping layer and duration formatting helper.

## Runtime interaction flow

1. Boot loads strict config via `ConfigLoader`.
2. `TargetDatabaseBootstrapService` ensures target DB objects exist (best effort).
3. `EngineOrchestratorService.startupRebuild()` performs full deterministic rebuild.
4. Scheduler calls `pollAndRebuild()` which uses incremental reads (`findNewerThan(lastProcessedTimestamp)`) and `applyIncremental(...)`.
5. Updated state is stored in `SequenceStateRepository`.
6. Controllers/reports read from repository snapshot.

## Report behavior

- Excludes candidate transitional rows (`timeoutSeconds > 0`).
- Supports UTC day filter: “active at any point during the day”.
- Uses `closedAtUtc` for closed sequence end boundary.
- Saves generated report files to `reportsDir` as `sequences_<all|dd-MM-yyyy>.xlsx`.

## Tests

- `SequenceEngineServiceImplTest` covers:
  - cold-start partial promotion;
  - in dedup;
  - single-camera close using last detection;
  - alert firing by elapsed time in incremental mode;
  - transitional candidate auto-creation;
  - SQL source parsing + rebuild sanity.
