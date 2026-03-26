# Agent Log

## 2026-03-26
- Implemented a full Spring Boot ALPR sequence engine skeleton based on `alpr-sequence-engine-spec.md`.
- Added structured configuration loader for `config.json` and created `config.json.example`.
- Implemented domain model, repositories, logic engine, alert sender, report builder, and REST controllers.
- Added unit tests for core sequence behaviors and SQL data parsing.
- Updated `README.md` and `TECHNICAL_SPEC.md` to document usage and implementation details.
- Added incremental polling path in orchestrator using `findNewerThan(lastProcessedTimestamp)` and engine `applyIncremental(...)`.
- Extended sequence engine with transitional `allowedAfter` candidate auto-start, elapsed-time candidate ticking, improved single-camera updates, and elapsed-time alert firing.
- Added sequence close timestamp + transitional close-timeout override handling.
- Implemented target PostgreSQL bootstrap DDL for role/database/schema/tables (best-effort).
- Enhanced report service with UTC-safe date filtering, closed-at day coverage, sheet headers, and auto-save to `reportsDir`.
- Expanded unit tests to cover partial promotion, dedup, single-camera close behavior, transitional candidate creation, and incremental alert timing.
- Updated README and TECHNICAL_SPEC for the new behavior.
- Fixed critical sequence logic issues from review:
  - pending transitional candidates are invalidated on any new detection for the plate;
  - closed sequences are no longer re-opened; new detections create a new sequence instance while closed history is retained.
- Added historical transitional backfill between stages when the gap exceeds `candidateTimeoutMinutes`.
- Hardened target DB bootstrap SQL with JDBC existence checks and quoted identifiers; removed invalid `CREATE DATABASE` inside `DO $$`.
- Expanded tests for candidate invalidation, post-close new sequence creation, and historical transitional backfill.
- Updated README and TECHNICAL_SPEC to document the above behavior.
