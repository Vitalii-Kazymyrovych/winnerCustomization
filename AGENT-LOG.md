# Agent log

- 2026-03-23: Reworked `StageSequenceProcessor` so transitional stages become stable reportable windows, single-camera stages use first-detection `In` and last-detection `Out`, and historical dated reports are rebuilt from full detection history before day filtering. Added regression tests for processor/report behavior and updated README/technical spec.

- 2026-03-23: Implemented transitional candidate-based sequence building, removed implicit transitional insertion, restored scheduled source refresh logging with configurable interval, expanded unit tests, and updated config/example docs.
