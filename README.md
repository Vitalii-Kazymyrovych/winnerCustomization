# winnerCustomization

`winnerCustomization` builds vehicle stage sequences from ALPR detections and exports them to XLSX. The system now uses a rewritten sequence engine with only three stage families: `real`, `transitional`, and `single_camera`.

## What the application does
- Loads detections from PostgreSQL.
- Groups detections by plate into chronological sequences.
- Builds stage windows using the new deterministic rules:
  - `real` stages open on `inTriggers` and keep a sticky `Out` timestamp from `outTriggers`.
  - `transitional` stages start as candidates and materialize only after `candidateTimeoutSeconds`.
  - `single_camera` stages use the first detection as `In` and the latest detection inside the timeout window as `Out`.
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

## HTTP endpoints
- `GET /report/sequences.xlsx` — build a report for all loaded detections.
- `GET /report/sequences.xlsx/{dd-MM-yyyy}` — build a report only for the specified day.
- `GET /config` — return current config as JSON or HTML.
- `GET /config/help` — show a short help page for the new configuration format.
- `POST /config` — save config as JSON or form payload.
- `GET /source/trigger-pull` — force a manual source pull with cooldown protection.

## Notifications
Notification rules are configured per camera. A timer starts when a matching detection arrives. If there are no later detections for the same plate on a different camera before `delaySeconds`, a notification is produced and can be dispatched through Telegram.

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
