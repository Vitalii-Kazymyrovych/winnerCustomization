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
