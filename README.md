# ALPR Sequence Engine

Spring Boot application that reads ALPR detections, builds stage sequences per plate, tracks durations and alerts, and provides XLSX reports.

## What it does

- Loads configuration from `config.json` placed next to the jar.
- Rebuilds full state from detections on startup and on each refresh interval.
- Processes stage types:
  - real (`in`/`out` triggers with direction tolerance)
  - transitional (trigger based)
  - single-camera (camera-only stage)
- Tracks active/closed sequences and stage durations in UTC.
- Sends alerts to Telegram (or logs alerts when messaging is disabled).
- Exposes reports:
  - `GET /report/sequences.xlsx`
  - `GET /report/sequences.xlsx/{dd-MM-yyyy}`
- Exposes JSON endpoint:
  - `GET /api/sequences`

## Configuration

1. Copy `config.json.example` to `config.json`.
2. Fill database credentials and workflow/alerts.
3. Keep `config.json` local only (already gitignored).

## Run

```bash
./mvnw spring-boot:run
```

## Test

```bash
./mvnw -B test
```

Unit tests are local-only (no live DB, Telegram, or external services).
