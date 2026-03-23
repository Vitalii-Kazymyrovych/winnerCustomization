# Agent log

- 2026-03-23: Changed report HTTP endpoints to trigger saving XLSX files into `reports.outputDirectory` instead of returning downloads, added output-directory validation and path resolution relative to `config.json`, added regression tests for saved report files, and updated README/technical spec.

- 2026-03-23: Reworked `StageSequenceProcessor` so transitional stages become stable reportable windows, single-camera stages use first-detection `In` and last-detection `Out`, and historical dated reports are rebuilt from full detection history before day filtering. Added regression tests for processor/report behavior and updated README/technical spec.

- 2026-03-23: Implemented transitional candidate-based sequence building, removed implicit transitional insertion, restored scheduled source refresh logging with configurable interval, expanded unit tests, and updated config/example docs.

- 2026-03-23: Updated `StageSequenceProcessor` so single-camera stages no longer auto-close on their own timeout; they now stay open across all same-camera detections and close on the last post detection only when another camera/stage starts. Added regression tests and refreshed README/technical spec.

