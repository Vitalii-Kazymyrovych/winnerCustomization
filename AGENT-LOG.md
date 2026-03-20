# Agent log

## 2026-03-16
- Updated XLSX report generation to a stage-row layout: each sequence now prints a plate row followed by stage rows (`Stage`, `Time in`, `Time out`, `Duration`, `Alerts`).
- Added per-stage duration formatting as `HH:mm:ss` and dynamic stage extraction based on available timestamps.
- Updated `ReportServiceTest` to validate the new report structure.
- Removed `<NEXT NUMBER SEQUENCE>` row from XLSX output; sequences are now separated only by plate marker row and following stage rows.

- Updated stage boundary logic so `Service in -> Post in` is treated as Service stage and `Post in -> Service out` as Post stage.
- Removed support for service post out cameras from configuration and sequence mapping.
- Updated report and duration calculations to use `serviceOutAt` as the Post stage end timestamp.
- Updated docs (`README.md`, `TECHNICAL_SPEC.md`) and `config.json.example` to reflect removal of post-out camera config.
- Removed redundant `serviceDriveInIn` camera slot from configuration and sequence camera type mapping.
- Updated sequence start logic to use `Drive in (in)` or `Service (in)` as valid start events.
- Updated `config.json.example`, `README.md`, and `TECHNICAL_SPEC.md` to reflect simplified service entry camera mapping.
- Fixed XLSX stage rendering for incomplete sequences: Service stage now closes at `postInAt` when `serviceOutAt` is not yet available, so a detected post entry always finalizes Service row in report.
- Added `ReportServiceTest` case covering `serviceIn + postIn + missing serviceOut` to verify Service time-out/duration and open Post row behavior.
- Updated `README.md` and `TECHNICAL_SPEC.md` stage-boundary notes to document Service end fallback to `postInAt`.
- Reworked notifications to DB-backed timed jobs: added `alert_jobs` storage (`AlertJobStorageService`) and `AlertSchedulerService` with two background loops (sync pending jobs + dispatch due jobs).
- Enabled Spring scheduling globally and moved Telegram sending out of `ReportService` into timed dispatcher.
- Added models for alert job type/record, updated `ReportServiceTest`, and added `AlertSchedulerServiceTest` coverage for upsert/cancel/dispatch behavior.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document timed notifications architecture, scheduler intervals, and DB load safeguards (indexed due query + idempotent upserts).
- Moved `ReportService` sequence DB refresh (`initialize` + `replaceAll`) to asynchronous background execution so `/report/sequences.xlsx` response is not delayed by sequence DB writes.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document non-blocking report download behavior and updated report data flow.

## 2026-03-17
- Reworked stage processing logic in `SequenceEngine` and sequence model to support:
  - auto-closing previous stage on next-stage detection,
  - multi-camera matching for Drive In / Service / Parking by `analyticsId + directionRange`,
  - post camera split into `Post In`/`Post Out` by direction ranges,
  - first-only `Post In` and overwrite behavior for repeated valid `Post Out`.
- Extended `SequenceRecord` with explicit first-service end, post-out and second-service-start timestamps.
- Updated report stage rows to `Service #1`, `Post`, `Service #2` where applicable.
- Updated tests (`SequenceEngineTest`, `ReportServiceTest`) and configuration template (`config.json.example`) for the new camera/stage model.
- Updated `README.md` and `TECHNICAL_SPEC.md` with new camera mapping and stage-processing rules.

- Renamed XLSX stage labels from `Service #1`/`Service #2` to repeated `Service` rows to match business terminology while preserving stage boundaries (`Service in`→`Post in` and `Post out`→`Service out`).
- Added optional `sourceTable.loadFrom` runtime config timestamp and source-query filtering (`created_at >= loadFrom`) to avoid scanning historic detections.
- Updated tests (`DetectionServiceTest`, `ReportServiceTest`) and docs (`README.md`, `TECHNICAL_SPEC.md`, `config.json.example`) for the new report naming and load-from behavior.
- Fixed runtime config `LocalDateTime` deserialization on packaged app startup by adding `jackson-datatype-jsr310` dependency and added `JacksonConfigTest` coverage for `sourceTable.loadFrom` JSON parsing.
- Updated `README.md` and `TECHNICAL_SPEC.md` startup/config notes to document Java time module requirement.
- Added report output persistence into configurable folder (`reports.outputDirectory`): each `/report/sequences.xlsx` build now also writes `sequences.xlsx` to disk.
- Extended XLSX generation with a second `Events` sheet containing columns `Номер`, `Камера`, `Этап`, `Тип события (In \\ Out)`, `Время`, and duration for `Out` events.
- Updated `ReportServiceTest` to cover second-sheet structure and configured report-file persistence.
- Updated `config.json.example`, `README.md`, and `TECHNICAL_SPEC.md` for the new report configuration and two-sheet report format.
- Updated `Events` XLSX sheet to remove the separate `Stage` column (stage is now implied by camera) and translated event-sheet headers to English (`Plate`, `Camera`, `Event type (In / Out)`, `Time`, `Duration for Out event`).
- Updated `ReportServiceTest` assertions for the new `Events` sheet structure and English headers.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document the new `Events` sheet columns.

## 2026-03-18
- Changed second XLSX sheet to a flat stage-per-row layout with columns `Plate`, `Stage`, `In time`, `Out time`, `Duration`, `Alarms`; added report coverage for `Backyard` rows.
- Added transition-camera support for `Drive-In -> Service` and `Service -> Drive-In`, including new Backyard stage generation and updated test-drive reset anchors in `SequenceEngine`/`SequenceRecord`.
- Updated `config.json.example`, `README.md`, and `TECHNICAL_SPEC.md` to document the new camera configuration, Backyard rules, and revised second-sheet format.
- Replaced `SequenceEngine` with a repeatable stage-timeline model that supports multiple occurrences of the same stage, one active stage at a time, 48-hour sequence rollover, timestamp normalization (`+1s` on equal timestamps), recovery/partial stages for exit-only events, Backyard as an explicit stage, and delayed Test-Drive materialization with timeout removal.
- Refactored `SequenceRecord` to store chronological stage windows (`Drive In`, `Service`, `Post`, `Parking`, `Backyard`, `Test-Drive`) with per-stage alerts, partial markers, and chronological report rendering.
- Updated `AlertSchedulerService` to schedule/cancel alerts per eligible `Drive In` / `Service` stage instead of relying on single fixed timestamps.
- Rewrote `SequenceEngineTest`, `ReportServiceTest`, `AlertSchedulerServiceTest`, and adjusted storage/integration tests to cover recovery flows, duplicate suppression, repeated posts, Test-Drive rules, Backyard generation, overwrite semantics for `Post Out`, report rows, and storage compatibility.
- Updated `README.md` and `TECHNICAL_SPEC.md` to describe the new sequence-processing rules and reporting model.

## 2026-03-19
- Deferred synthetic `Service` creation after recovery-only `Post Out`: repeated same-post `Post Out`/`Post In` detections now stay as Post-only rows instead of producing noisy `Post -> Service -> Post` transitions.
- Added `SequenceEngineTest` and `ResultsRegressionTest` coverage for production plates (`AA4444PO`, `KA7828BB`, `KA1163K`, `BK0542KA`) that previously showed those false transitions.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document the delayed post-recovery service materialization rule.
- Fixed `SequenceEngine` so `Service -> Drive-In` always closes the currently active stage before creating a delayed `Test-Drive` candidate; this removes illegal overlaps between `Service` and `Test-Drive`.
- Added sequence finalization guard so transition-only records without concrete stages are dropped instead of leaking `No stages` into the report.
- Updated direction-range matching to support wrap-around ranges (for example `270 -> 90`) and to use an exclusive upper bound, which removes ambiguous boundary matches like `90`/`270`.
- Expanded tests with regression coverage for transition-only records, wrapped direction ranges, service-to-test-drive handoff, report omission of empty records, and a committed-results dataset replay (`results/config.json.production` + `results/alpr_detections.sql`).
- Updated `README.md` and `TECHNICAL_SPEC.md` to document wrap-around direction handling, omission of empty records, and the stricter `Service -> Drive-In` transition behavior.
- Restored post numbers/names in XLSX reports by preserving `servicePosts[].postName` inside stage windows and rendering it in both `Sequences` and `Events` sheets instead of the generic `Post` label.
- Updated `SequenceEngineTest`, `ReportServiceTest`, and `ResultsRegressionTest` to cover named post labels, and refreshed `README.md` / `TECHNICAL_SPEC.md` notes for post-name report rendering.
- Added a second XLSX endpoint `/report/sequences.xlsx/dd-MM-yyyy` that builds sequences only from detections inside the requested day window and returns/persists `sequences-dd-MM-yyyy.xlsx`.
- Extended `DetectionService`, `ReportService`, and `ReportController` plus tests to support date-bounded detection loading, dated attachment naming, and dated file persistence.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document the new day-scoped report behavior and endpoint.
- Reworked `SequenceEngine` Post handling into sticky logic: duplicate `Post In` on the same configured post is ignored, duplicate `Post Out` only refreshes `outTimeCandidate`, sticky `Post` closes only on another stage/finalization, and synthetic `Service` is created only when a later transition proves it is needed.
- Updated `ReportService` so open sticky `Post` rows keep empty `Out time` but still show `Duration` based on `SequenceRecord.finishedAt`.
- Expanded `SequenceEngineTest` and `ReportServiceTest` coverage for sticky Post duplicates, synthetic service insertion, open Post duration rendering, and cross-post transitions; refreshed `README.md` / `TECHNICAL_SPEC.md` to describe the new Post-stage invariants.
- Fixed replayed production regressions where `Service Out` / `Parking Out` detections were silently dropped while `Backyard` was already active; such detections are now preserved as partial recovery rows without closing the active `Backyard`.
- Normalized XLSX timestamp rendering to `yyyy-MM-dd HH:mm:ss` so reports no longer expose Java `LocalDateTime` `T`/nanosecond formatting.
- Expanded regression coverage with explicit assertions for repeated `Service Out` recoveries inside `Backyard`, `Parking Out` recovery inside `Backyard`, and results-dataset plates that previously lost those rows.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document active-`Backyard` recovery handling and the new XLSX timestamp format.

## 2026-03-20

- Clarified that `workflow` is now the preferred runtime config, removed duplicate legacy `cameras`/`timing` blocks from `config.json.example`, and updated `README.md` / `TECHNICAL_SPEC.md` to document which config sections are required at startup versus editable live in the web UI (with DB credential changes still requiring restart).

- Installed PostgreSQL 16 locally, created `source_database_name` / `sequences_database_name`, imported `results/alpr_detections.sql`, configured local `config.json` from the legacy production camera mapping, validated the running jar endpoints (`/config`, `/report/sequences.xlsx`, `/report/sequences.xlsx/18-03-2026`, `/source/trigger-pull`), and documented the operational verification in `OPERATION_REPORT.md`.
- Added live runtime configuration management: `RuntimeConfig` now validates/saves/reloads `config.json`, keeps the active config in memory atomically, and backfills a generated `workflow` section from legacy camera/timing config via `WorkflowDefaultsFactory`.
- Added `/config` JSON + HTML endpoints (`ConfigController`) so operators can inspect and edit configuration without restarting the service.
- Updated report generation to append a `Sequence Closed` row (with `finishedAt` in `Time out`) after each non-empty closed sequence on the `Sequences` sheet.
- Extended `config.json.example` with the new declarative `workflow` section and updated `README.md` / `TECHNICAL_SPEC.md` to describe live config editing, workflow config, and the new report row.
- Added `ConfigControllerTest` coverage and updated `ReportServiceTest` assertions for the `Sequence Closed` row.
- Reworked `SequenceEngine` into a workflow-driven builder that reads dynamic stages/triggers from `workflow.stages[]`, supports generic candidate/sticky/partial/intermediate transition policies, and stores dynamic stage names/labels in `SequenceRecord` instead of a fixed enum.
- Updated `WorkflowDefaultsFactory` to generate richer default workflow metadata (`allowedNextStages`, sticky timeouts, candidate cancel events, dynamic post labels) from legacy camera/timing config.
- Switched report/alert/test models to dynamic `StageWindow` metadata, added workflow-mode validation in `RuntimeConfig`, and refreshed tests/docs (`README.md`, `TECHNICAL_SPEC.md`) to cover dynamic workflow behavior, `/config` validation, and dataset regression handling.

- Added a dedicated Ukrainian `/config/help` page plus richer `/config` HTML navigation so operators can read detailed setup instructions directly in the app.
- Extended `ConfigControllerTest` coverage for the help page and updated `README.md` / `TECHNICAL_SPEC.md` to document the new config-help route.
