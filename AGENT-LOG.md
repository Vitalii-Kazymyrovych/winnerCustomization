# Agent log

## 2026-03-16
- Updated XLSX report generation to a stage-row layout: each sequence now prints a plate row followed by stage rows (`Stage`, `Time in`, `Time out`, `Duration`, `Alerts`).
- Added per-stage duration formatting as `HH:mm:ss` and dynamic stage extraction based on available timestamps.
- Updated `ReportServiceTest` to validate the new report structure.
- Removed `<NEXT NUMBER SEQUENCE>` row from XLSX output; sequences are now separated only by plate marker row and following stage rows.

