# Agent Log

## 2026-03-13
- Implemented configurable JSON runtime config (`config.json`) loading with two database credentials sections (source + sequence).
- Added camera map model including direction range support and service posts camera list.
- Implemented source detection reader from PostgreSQL `videoanalytics.alpr_detections` (table name configurable).
- Implemented sequence building engine for drive-in/service/post/parking stages with alert generation and test-drive reset logic.
- Implemented sequence storage in separate PostgreSQL DB (`vehicle_sequences` table).
- Added Telegram notification sender (toggleable via config).
- Added XLSX report generation and browser-download endpoint `/report/sequences.xlsx`.
- Added `config.json.example` template and ignored `config.json`.
- Updated Maven dependencies and unit tests for sequence logic and direction filtering.
- Updated README and technical specification to reflect architecture and usage.
- Added comprehensive unit tests for all service classes (`DetectionService`, `SequenceStorageService`, `ReportService`, `TelegramNotifier`) and kept `SequenceEngine` coverage.
- Updated README test section and refreshed TECHNICAL_SPEC with service methods/interactions and full test coverage details.
- Verified with `./mvnw -B test` (10 tests passing).
- Added manual source pull endpoint `GET /source/trigger-pull` for external analytics callbacks.
- Implemented `SourcePullTriggerService` with concurrency protection (single active trigger) and 30-second cooldown after successful trigger.
- Added `TimeConfig` clock bean to make trigger timing deterministic and testable.
- Added `SourcePullTriggerServiceTest` covering success flow, cooldown, and parallel-call protection.
- Updated README and TECHNICAL_SPEC with new endpoint behavior and response statuses.

- Added detailed console logging across controllers, configuration loading, JDBC initialization, source pull trigger, sequence/report services, storage and Telegram notification flow.

## 2026-03-15
- Fixed Spring Boot startup failure by adding explicit `ObjectMapper` bean configuration (`JacksonConfig`) used by `RuntimeConfig` constructor injection.
- Verified with `./mvnw -B test` (13 tests passing).
- Improved sequence database failure diagnostics in `SequenceStorageService.initialize()`: JDBC connection failures now raise an actionable `IllegalStateException` that includes configured host/port/db/user and explicit checks to perform.
- Updated `SequenceStorageServiceTest` for constructor changes.
- Updated README and TECHNICAL_SPEC with PostgreSQL troubleshooting steps for `database does not exist` errors and new diagnostic behavior.
- Verified with `./mvnw -B test` (13 tests passing).
- Added startup PostgreSQL bootstrap flow: app now checks and auto-creates `sequenceDatabase.db` on first start using new `rootDatabase` credentials from `config.json`.
- Added `DatabaseBootstrapService` and wired it into `JdbcConfig` before sequence datasource initialization.
- Added `DatabaseBootstrapServiceTest` covering create-on-missing and skip-when-exists behavior without live DB.
- Updated `config.json.example`, README, and TECHNICAL_SPEC for new root/admin configuration and first-start behavior.
- Fixed PostgreSQL bootstrap bug in `DatabaseBootstrapService`: replaced `queryForObject("select 1 ...")` logic with `select exists(...)` to correctly handle missing DB without `EmptyResultDataAccessException`.
- Updated `DatabaseBootstrapServiceTest` for boolean existence checks.
- Installed PostgreSQL 16 in the environment, started local cluster, and validated database operations end-to-end.
- Added `PostgresDatabaseOperationsIntegrationTest` to verify real PostgreSQL flow: source reads, sequence DB auto-creation, sequence table init, and persistence writes.
- Updated README and TECHNICAL_SPEC with new troubleshooting note and integration-testing details.
- Verified with `./mvnw -B test` (16 tests passing, including PostgreSQL integration test).
