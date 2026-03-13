# winnerCustomization

Spring Boot script that:

1. Reads ALPR detections from source PostgreSQL (`videoanalytics.alpr_detections` or configurable table).
2. Builds car movement sequences across configured camera stages.
3. Stores computed sequences in a separate PostgreSQL database.
4. Exposes XLSX report download endpoint.

## Run

1. Build:
   ```bash
   ./mvnw -B test
   ./mvnw -B package
   ```
2. Put `config.json` near the jar (copy from `config.json.example` and fill values).
3. Start app:
   ```bash
   java -jar target/winnerCustomization-0.0.1-SNAPSHOT.jar
   ```
4. Download report from browser:
   - `http://localhost:8080/report/sequences.xlsx`

## Configuration

Use `config.json` (not committed) with:

- Source DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Sequence DB credentials: `host`, `port`, `db`, `schema`, `user`, `password`.
- Camera mapping list for:
  - Drive in (in)
  - Drive in (out)
  - Service-Drive in (in)
  - Service (in)
  - Service posts (array: post in/out)
  - Service (out)
  - Parking (in)
  - Parking (out)
- Direction ranges per camera (`from`/`to`) where null means no filtering.
- Alert timing thresholds.
- Telegram notifications toggle and credentials.

## Notes

- `config.json` is in `.gitignore`.
- Use `config.json.example` as the template.
- The app creates `vehicle_sequences` table in sequence DB if it does not exist.
