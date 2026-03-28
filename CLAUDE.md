# Claude Code Instructions

## Project
Java 17 / Spring Boot application for ALPR sequence processing.
Build: `./mvnw -B test` (run after every code change)

## Rules
- Logic spec: `logic.md` — read ONLY the sections relevant to the current task
- Don't modify: logic.md, README.md, TECHNICAL_SPEC.md without direct request
- Don't do: git add, git commit, git push — I handle git myself
- Tests must not call external services (Telegram, DB)
- `config.json` is gitignored, never commit it

## Testing
If tests fail: review exception → fix code → run tests → repeat