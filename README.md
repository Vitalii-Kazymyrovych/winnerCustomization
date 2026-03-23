# winnerCustomization

`winnerCustomization` builds vehicle stage sequences from ALPR detections and exports them to XLSX. The system uses three stage families only: `real`, `transitional`, and `single_camera`.

## What the application does
- Loads detections from PostgreSQL.
- Groups detections by plate into chronological sequences.
- Builds stage windows using deterministic rules:
  - `real` stages open on `inTriggers` and keep a sticky `Out` timestamp from `outTriggers`.
- `transitional` stages start as candidates and materialize only after `candidateTimeoutSeconds`.
- `real` stages treat `Out` as a sticky boundary marker: it updates report `Out time`, but the stage remains the active context until another stage starts or the sequence finally closes.
- `transitional` stages remain candidates first: repeated detections from the same trigger source extend the candidate timeout, materialization happens only after the quiet gap is long enough, and `showInReportIfIncomplete = false` removes only terminal incomplete transitional rows when their own close timeout expires.
- Transitional trigger cameras are now honored only when the currently active/last concrete stage matches `allowedAfter`, so standalone Backyard/Test-Drive detections do not create impossible stage rows.
- Transitional candidates are also spawned immediately after a configured `allowedAfter` stage finishes, even if no dedicated transitional-camera detection arrives; for example `Parking -> Backyard` can now appear from a `Parking Out` event alone.
- `single_camera` stages keep the first detection as `In`, refresh `lastSeenAt` on every repeated detection, split into a fresh stage after `timeoutSeconds` of silence, close at the next concrete stage boundary when one arrives, and stay open in the report only while neither their own timeout nor the sequence timeout has expired.
- Generates `Sequences` and `Events` sheets in XLSX.
- Persists built sequences and pending notifications through repositories.
- Can schedule Telegram notifications when a plate stays on a configured camera for too long.

## Configuration
Create `config.json` next to the jar by copying `config.json.example`.

### Main runtime blocks
- `sequenceCloseTimeoutMinutes` — global inactivity timeout for closing a sequence.
- `allowTransitionalAfterSingleCamera` — optional global flag to allow transitional candidates after single-camera stages.
- `duplicateSuppressionSeconds` — duplicate detection suppression window.
- `notifications[]` — camera-based alert rules.
- `realStages[]` — main business stages with explicit `In` and `Out` triggers.
- `transitionalStages[]` — intermediate candidates.
- `singleCameraStages[]` — post-like sticky stages.
- `messaging` — Telegram delivery settings for dispatched notifications.
- `reports.outputDirectory` — optional folder where generated XLSX files are stored.

## XLSX report format
### `Sequences`
- Header columns: `Stage`, `In time`, `Out time`, `Duration`, `Alerts`.
- Each sequence starts with a centered plate row placed in the `Out time` column.
- Closed sequences end with a centered `Sequence closed` row, also placed in the `Out time` column.
- Open stages keep `Out time` empty and compute `Duration` as `reportGeneratedAt - inTime`.

### `Events`
- Header columns: `Plate`, `Stage`, `In time`, `Out time`, `Duration`, `Alerts`.
- The technical `Type` column is intentionally omitted from both sheets.

## HTTP endpoints
- `GET /report/sequences.xlsx` — build a report for all loaded detections.
- `GET /report/sequences.xlsx/{dd-MM-yyyy}` — build a report only for the specified day.
- `GET /config` — return current config as JSON or HTML.
- `GET /config/help` — show a short help page for the new configuration format.
- `POST /config` — save config as JSON or form payload.
- `GET /source/trigger-pull` — force a manual source pull with cooldown protection.

## Notifications
Notification rules are configured per camera. A timer starts when a matching detection arrives. If there are no later detections for the same plate on a different camera before `delaySeconds`, a notification is produced and can be dispatched through Telegram. Report alerts are attached only to sequences and stage rows of that same plate, and identical `(plate, trigger time, message)` notifications are deduplicated before enrichment so the same alert text is not repeated in one row.

## Transitional-stage closure notes
- `transitionalStages[].sequenceCloseTimeoutOverrideSeconds = 0` means “close the sequence immediately after the transitional stage has been confirmed/materialized”.
- This is especially important for `Backyard`: the row still appears in the report, but it now closes at the transitional confirmation timestamp instead of remaining open for many hours until report generation.
- Once a new `real` or `single_camera` stage starts after that transitional row, the temporary override is cleared immediately. This prevents the next normal `Parking`/`Post` interval from being force-closed on the very next detection and avoids bogus multi-day open durations in XLSX.

## Running locally
```bash
cp config.json.example config.json
./mvnw spring-boot:run
```

## Tests
Run unit tests with:
```bash
./mvnw -B test
```
This now also generates a JaCoCo HTML coverage report in `target/site/jacoco/index.html` so you can inspect which branches were exercised.
The automated regression suite replays the full committed `results/` dataset, checks every plate / sequence for compact single-camera stage rendering and non-overlapping stage order, compares the generated stage timeline against the committed logic snapshot `results/expected_sequences_logic.csv`, and additionally verifies controllers, JDBC adapters, runtime-config persistence, notification scheduling, bootstrap helpers, and startup wiring with isolated unit tests.
