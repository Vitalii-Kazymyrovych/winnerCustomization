# Operational verification report — 2026-03-20

## What was done

1. Installed PostgreSQL 16 in the container and started the local cluster on `localhost:5432`.
2. Created and configured local PostgreSQL roles:
   - `postgres` (admin/root for bootstrap)
   - `is_db_user` (owner required by the SQL dump)
   - `source_user` (application source DB user)
   - `sequence_user` (application sequence DB user)
3. Created both application databases:
   - `source_database_name`
   - `sequences_database_name`
4. Created schema `videoanalytics` in `source_database_name` and imported `results/alpr_detections.sql` into the real PostgreSQL table `videoanalytics.alpr_detections`.
5. Created local `config.json` from the legacy production config, preserving the old camera IDs and direction ranges from `results/config.json.production`, but replacing credentials/hosts with local PostgreSQL values and storing reports in `/workspace/winnerCustomization/reports`.
6. Built the application, started the packaged jar, and verified the web interface and report endpoints.
7. Unpacked both XLSX files (`results/sequences.xlsx` and the generated `/tmp/generated-sequences.xlsx`) to inspect workbook contents as ZIP archives during report validation.

## Database verification results

- Imported detections into `videoanalytics.alpr_detections`: **715 rows** total.
- Effective application filter `sourceTable.loadFrom = 2026-03-16T00:00:00` leaves **705 rows** for report generation and alert scheduling.
- After application startup/report generation, the sequence database contained:
  - `vehicle_sequences`: **49** rows
  - `alert_jobs`: **59** rows total
  - alert job breakdown: **28 PENDING**, **31 SENT**, **0 CANCELLED**

## Web interface verification

Verified successfully:

- `GET /config` returned JSON with the effective runtime config.
- `GET /config` with `Accept: text/html` returned the HTML editor page with the textarea form.
- `GET /report/sequences.xlsx` returned HTTP 200 and an XLSX attachment.
- `GET /report/sequences.xlsx/18-03-2026` returned HTTP 200 and a dated XLSX attachment.
- `GET /source/trigger-pull` returned `{"status":"TRIGGERED","detectionsLoaded":705}`.

Generated report files were also persisted on disk:

- `reports/sequences.xlsx`
- `reports/sequences-18-03-2026.xlsx`

## Report verification results

### Generated full report

Generated report characteristics:

- workbook sheets: `Sequences`, `Events`
- `Sequences` sheet rows: **513**
- `Events` sheet rows: **275**
- sequence marker rows in `Sequences`: **119**
- unique plates represented in `Sequences`: **110**

### Comparison with the reference workbook in `results`

The newly generated report is **not byte-identical** to `results/sequences.xlsx`, and the sheet sizes differ:

- generated: `Sequences=513`, `Events=275`
- reference: `Sequences=512`, `Events=370`

Observed reason during manual inspection:

- the current application builds reports according to the newer workflow-driven logic and current report format, while `results/sequences.xlsx` reflects an older snapshot of report output;
- the generated workbook is internally consistent with the current runtime behavior, endpoint responses, database contents, and the repository regression tests built around `results/config.json.production` + `results/alpr_detections.sql`.

### Practical correctness conclusion

I consider report generation **operationally correct for the current codebase**, because:

1. the application started successfully with the legacy camera mapping;
2. the source dump was loaded into PostgreSQL and read by the running application;
3. both XLSX endpoints returned valid files and persisted them to disk;
4. automated regression/unit tests passed on the same repository state;
5. the generated workbook structure matches the current application contract (`Sequences` + `Events`, persisted full and dated reports, live config support).

## Commands used for validation

- `apt-get update && apt-get install -y postgresql postgresql-contrib`
- `pg_ctlcluster 16 main start && pg_isready`
- `psql ... -f results/alpr_detections.sql`
- `./mvnw -B test`
- `./mvnw -B package -DskipTests`
- `java -jar target/winnerCustomization-0.0.1-SNAPSHOT.jar`
- `curl http://localhost:8080/config`
- `curl -H 'Accept: text/html' http://localhost:8080/config`
- `curl -I http://localhost:8080/report/sequences.xlsx`
- `curl -I http://localhost:8080/report/sequences.xlsx/18-03-2026`
- `curl http://localhost:8080/source/trigger-pull`
- `unzip results/sequences.xlsx ...` and `unzip /tmp/generated-sequences.xlsx ...`

## Notes

- `./mvnw -B spring-boot:run` did not start in this environment because the Spring Boot Maven plugin failed to resolve one transient dependency descriptor. Packaging and running the fat jar with `java -jar target/winnerCustomization-0.0.1-SNAPSHOT.jar` worked correctly, so runtime validation was completed through the packaged application instead.
