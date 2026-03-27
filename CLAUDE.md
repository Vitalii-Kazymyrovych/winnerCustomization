# Claude Code Instructions

## Repository
- GitHub: https://github.com/Vitalii-Kazymyrovych/winnerCustomization
- Main branch: `main`
- Pushing commits to origin is expected and permitted in this project.

## Project
Java 17 / Spring Boot application for ALPR (license plate recognition) sequence processing.
Build tool: Maven Wrapper (`./mvnw`)

## Git workflow
- Always commit and push after completing a task
- Use descriptive commit messages
- Do NOT force-push or reset hard — history must be preserved
- Branches follow the pattern: `claude/<short-task-description>`

## Tests
- Run after every code change: `./mvnw -B test`
- Cover all logic with as many tests as possible
- Tests must not call external services (Telegram, DB, VEZHA) or require live services
- Use real data from the `.sql` file in the root folder to cover all possible cases
- If tests fail, follow this loop:
  1. Review exceptions
  2. Rewrite code
  3. Run tests
  4. If still failing, repeat from step 1
  5. If passing, commit

## Logs & docs
- Before starting any task, read `AGENT-LOG.md` to understand what was done earlier
- After completing any task, briefly log changes made to `AGENT-LOG.md`
- After any code change, update both:
  - `TECHNICAL_SPEC.md` — developer-oriented: all classes, interfaces, methods, and interactions
  - `README.md` — user-oriented: how to use, configure, and what the app does

## Config
- `config.json` is gitignored — never commit it
- Only commit `config.json.example` with placeholder values and short notes on how to fill each field
- In production, `config.json` lives in the same folder as the `.jar` file
