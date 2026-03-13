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
