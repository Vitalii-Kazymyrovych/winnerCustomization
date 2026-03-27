# AGENT LOG

## 2026-03-27
- Installed PostgreSQL 16 in the execution environment and initialized a local cluster.
- Created source database objects and loaded `alpr_detections.sql` data (`1439` rows) into `source_db.videoanalytics.alpr_detections`.
- Added runtime `config.json` (ignored by git) to point the app to local PostgreSQL source/target databases.
- Fixed target bootstrap/runtime SQL issues:
  - Quoted reserved column name `"full"` in DDL/DML for `stages`.
  - Granted sequence privileges to the sequence DB user and added default privileges for sequences.
- Ran unit tests successfully (`./mvnw -B test`).
- Ran the application, generated report via `GET /report/sequences.xlsx`, and converted workbook sheets to CSV files under `reports/csv/`.
- Kept `.xlsx` report file out of commits and added ignore rule for generated report xlsx files.
- Analyzed generated reports (CSV) against alpr-sequence-engine-spec and identified two bugs in `SequenceEngineServiceImpl`:
  - **Bug 1**: `closeActiveStage()` and `closeActiveStageForSingleCamera()` never computed `durationSeconds` for closed stages, causing all mid-sequence stages (e.g. Backyard transitionals, single-camera stages closed by the next stage) to show blank duration in reports. Fixed by adding `Duration.between(inTime, outTime)` computation to both close methods.
  - **Bug 2**: `handleOutTrigger()` called `seq.getStages().add(partial)` unconditionally after `createPartialStage()`, which returns `null` when it promotes an existing partial to full. This would add `null` to the stage list. Fixed with a null-check before `add()`.
- Added 3 targeted regression tests covering both bugs; all 44 tests pass.
