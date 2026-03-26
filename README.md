# ALPR Sequence Engine

Spring Boot application that reads ALPR detections, builds per-plate sequences, evaluates stage transitions incrementally, tracks alerts, and exports XLSX reports.

## What it does

- Loads configuration from `config.json` placed next to the jar.
- Performs startup rebuild, then **incremental polling** using `lastProcessedTimestamp` and `findNewerThan(...)`.
- Processes stage types:
  - real (`in`/`out` triggers with circular direction tolerance)
  - transitional (camera trigger + `allowedAfter` candidate auto-start)
  - single-camera (camera-only stage)
- Maintains stage duration and sequence lifecycle in UTC.
- Closes sequences using default timeout or transitional override timeout (`sequenceCloseTimeoutOverrideMinutes`).
- Creates/cancels/suppresses alerts and fires them by real elapsed UTC time.
- Sends alerts to Telegram (or logs when messaging is disabled).
- Builds XLSX reports and saves them to `reportsDir`.

## Endpoints

- `GET /api/sequences` — current sequence state as JSON.
- `GET /report/sequences.xlsx` — full report.
- `GET /report/sequences.xlsx/{dd-MM-yyyy}` — UTC-day filtered report.

## Configuration

1. Copy `config.json.example` to `config.json`.
2. Fill database credentials, workflow, and alerts.
3. Keep `config.json` local only (already ignored by git).

## Run

```bash
./mvnw spring-boot:run
```

## Test

```bash
./mvnw -B test
```

Unit tests are local-only and do not call live VEZHA/Telegram/DB services.
