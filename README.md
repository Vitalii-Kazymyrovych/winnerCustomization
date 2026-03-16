# winnerCustomization

Spring Boot script that:

1. Reads ALPR detections from source PostgreSQL (`videoanalytics.alpr_detections` or configurable table).
2. Builds car movement sequences across configured camera stages.
3. Stores computed sequences in a separate PostgreSQL database.
4. Exposes XLSX report download endpoint in a stage-row layout (dynamic per sequence, not fixed stage columns).
5. Provides manual trigger endpoint to force source-table pull with anti-parallel and cooldown protection.

## Run

1. Build and run unit tests:
   ```bash
   ./mvnw -B test
   ```
2. Build jar:
   ```bash
   ./mvnw -B package
   ```
3. Put `config.json` near the jar (copy from `config.json.example` and fill values).
4. Start app:
   ```bash
   java -jar target/winnerCustomization-0.0.1-SNAPSHOT.jar
   ```
5. Optional manual trigger (for external analytics callbacks):
   - `http://localhost:8080/source/trigger-pull`
   - returns `200` on success, `409` if a trigger is currently running, `429` during cooldown.
6. Download report from browser:
   - `http://localhost:8080/report/sequences.xlsx`

## Configuration

Use `config.json` (not committed) with:

- Source DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Sequence DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Root PostgreSQL credentials for first-start bootstrap: `rootDatabase.host`, `rootDatabase.port`, `rootDatabase.user`, `rootDatabase.password`, optional `rootDatabase.maintenanceDb` (default `postgres`). Bootstrap now ensures both sequence database and its DB user/permissions.
- Source detections table name.
- Camera mapping list for:
  - Drive in (in)
  - Drive in (out)
  - Service (in)
  - Service posts (array: post in cameras)
  - Service (out)
  - Parking (in)
  - Parking (out)
- Direction ranges per camera (`from`/`to`) where null means no filtering.
- Alert timing thresholds.
- Telegram notifications toggle and credentials.

## Trigger endpoint behavior

- Endpoint: `GET /source/trigger-pull`.
- Performs immediate read from source detections table.
- Protection from duplicate/parallel calls:
  - only one running trigger at a time;
  - 30-second cooldown after successful trigger.
- Suitable for situations where multiple analytics systems can call the endpoint simultaneously.

## Startup troubleshooting

- If startup fails with `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`, ensure you are running a build that includes `JacksonConfig` (adds explicit `ObjectMapper` bean for runtime config loading).
- On first start, the app attempts to auto-create `sequenceDatabase.db` using `rootDatabase` credentials.
- If startup fails with `Incorrect result size: expected 1, actual 0` while checking `pg_database`, upgrade to a build that uses `select exists(...)` for database presence checks (current implementation handles missing DB correctly).
- If startup/report still fails with sequence DB connection errors, check:
  1. `rootDatabase` credentials really have rights to read `pg_database` and `CREATE DATABASE`.
  2. `rootDatabase.host`/`port` point to the same PostgreSQL instance as `sequenceDatabase`.
  3. `sequenceDatabase.user`/`password` are set correctly. Current bootstrap will create/update this role and grant DB/schema permissions automatically when root credentials are valid.
  4. Manual validation:
     - `psql -h <rootHost> -p <rootPort> -U <rootUser> -d postgres -c "select datname from pg_database"`
     - `psql -h <seqHost> -p <seqPort> -U <seqUser> -d <sequenceDb>`

## Notes

- Detailed console logging is enabled for runtime actions (config load, endpoint calls, source pull triggers, sequence build/storage, report generation, notifications).
- XLSX report format is stage-oriented: for each plate, the sheet includes stage rows with `Stage`, `Time in`, `Time out`, `Duration` (`HH:mm:ss`) and per-row alert text. Service stage is interpreted as `Service in -> Post in`, and Post stage as `Post in -> Service out`.
- `config.json` is in `.gitignore`.
- Use `config.json.example` as the template.
- The app creates `vehicle_sequences` table in sequence DB if it does not exist.

## Tests

Unit tests cover sequence orchestration and every service class:
- `SequenceEngineTest`
- `DetectionServiceTest`
- `SequenceStorageServiceTest`
- `ReportServiceTest`
- `TelegramNotifierTest`
- `SourcePullTriggerServiceTest`

Unit tests remain offline and use mocks.

Additionally, `PostgresDatabaseOperationsIntegrationTest` validates end-to-end PostgreSQL operations against a local PostgreSQL instance (`localhost:5432`, user/password `postgres/postgres`). If PostgreSQL is unavailable in CI/local env, the test is skipped via JUnit assumption:
- sequence DB auto-creation via `DatabaseBootstrapService`;
- source detection read via `DetectionService`;
- sequence table initialization and write flow via `SequenceStorageService`.
