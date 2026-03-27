# AGENT LOG

## 2026-03-27 (session 3)
- Fixed **Bug 1 (sequences not closing after sequenceCloseTimeoutMinutes)** for both paths:
  - **Historical/startup**: added `checkAndCloseForDetectionGap()` called at the top of `processOneDetection()`. When the timestamp gap between consecutive detections for the same plate exceeds `sequenceCloseTimeoutMinutes` (or the active transitional's override), the current sequence is closed before the new detection is processed. This correctly produces multiple sequences for a plate with disjoint visits in historical data.
  - **Runtime/polling**: `closeSequence()` now appends to a `newlyClosedSequences` list. `pollAndProcess()` passes this list to `updateActive(activeSequences, newlyClosed)` so closed sequences are reinserted in the DB as `active=false` instead of disappearing. `performInitialLoad()` calls `clearNewlyClosedSequences()` after `rewriteAll()` to prevent the first poll from re-inserting startup-closed sequences.
- Fixed **Bug 2 (transitional materializing in less than candidateTimeoutMinutes)** for both paths:
  - **Runtime**: initial candidate timeout changed from `candidateTimeoutMinutes * 60` to `candidateTimeoutMinutes * 60 + 1` seconds (in both `createTransitionalCandidate` and `resetCandidateOnStageOut`), so duration at materialization = exactly `candidateTimeoutMinutes * 60` seconds (the `+1s inTime` offset was shaving 1 second off).
  - **Historical**: `insertHistoricalTransitionals` now compares `gapSeconds > candidateTimeoutMinutes * 60L` (seconds-precise) instead of `gapMinutes > candidateTimeoutMinutes` (which truncated and missed gaps in the range `[candidateTimeoutMinutes, candidateTimeoutMinutes + 1)` minutes).
- Updated `TargetRepository.updateActive()` signature to accept `newlyClosedSequences`; writes all stages/alerts for closed sequences regardless of their `active` flag.
- Added `getNewlyClosedSequences()` / `clearNewlyClosedSequences()` to `SequenceEngineService` interface and implementation.
- Added 7 new regression tests; all 51 tests pass.

## 2026-03-27 (session 2)
- Refactored runtime DB update (polling step 7) to use a new `TargetRepository.updateActive()` method instead of `rewriteAll()`.
- `updateActive()` deletes only rows where `active = true` in the DB, then reinserts the current active state — closed sequences, inactive stages, and inactive alerts are never touched during polling.
- Added `nextSeqId / nextStageId / nextAlertId` tracking fields to `TargetRepository`; `rewriteAll` sets them so `updateActive` can assign new IDs without colliding with existing closed-sequence rows.
- Added `SequenceEngineServiceImpl.getActiveSequences()` to expose only the active sequence map for the polling write path.
- `SchedulerServiceImpl.performInitialLoad()` still calls `rewriteAll(getAllSequences())` for the full startup rewrite.
- All 44 tests pass.

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
