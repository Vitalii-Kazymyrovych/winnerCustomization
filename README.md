# Winner Customization (ALPR Sequence Engine)

Spring Boot application that reads ALPR detections from a source PostgreSQL database, builds vehicle movement sequences, writes transformed sequence data into a target PostgreSQL database, and generates XLSX/CSV reports.

## Main Features
- Poll detections from `videoanalytics.alpr_detections`.
- Build stage-based plate sequences (real, transitional, and single-camera stages).
- Enforce timeout-safe lifecycle behavior: sequences auto-close after configured inactivity timeout and transitional candidates never materialize before the full configured candidate timeout window.
- Track and persist alerts with timeout logic.
- Rewrite full computed state into target DB tables:
  - `alpr_sequences.sequences`
  - `alpr_sequences.stages`
  - `alpr_sequences.alerts`
- Generate full/day sequence reports with HTTP endpoints.

## Configuration
Runtime configuration is loaded from `config.json` in working directory (or near the jar).

- Example file: `config.json.example`
- Do **not** commit real credentials.
- `config.json` is gitignored.

## Local Run (PostgreSQL + App)
1. Install PostgreSQL and start cluster.
2. Create source DB/schema and load sample data:
   - `source_db`
   - `videoanalytics` schema
   - import `alpr_detections.sql`
3. Provide `config.json` with valid DB credentials.
4. Run tests:
   - `./mvnw -B test`
5. Start app:
   - `./mvnw -B spring-boot:run`

## Report Endpoints
- Full report: `GET /report/sequences.xlsx`
- Day report: `GET /report/sequences.xlsx/{dd-MM-yyyy}`

Generated report files are written to `reportsDir` from config (default `./reports`).

## CSV Artifacts
For repository-safe report artifacts, convert workbook sheets to CSV and commit CSV files (not XLSX). Current generated files are under:
- `reports/csv/Sequences_Active.csv`
- `reports/csv/Sequences_Closed.csv`
- `reports/csv/Events.csv`
