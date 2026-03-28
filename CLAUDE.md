# Claude Code Instructions

## Project
Java 17 / Spring Boot application for ALPR sequence processing.
Build: ./mvnw -B test (run after every code change)

## Rules
- Logic spec: logic.md — read ONLY the sections relevant to the current task
- Don't modify: logic.md, README.md, TECHNICAL_SPEC.md without direct request
- Don't do: git add, git commit, git push — I handle git myself
- Tests must not call external services (Telegram, DB)
- config.json is gitignored, never commit it

## Testing
If tests fail: review exception → fix code → run tests → repeat

## File editing
- NEVER use string replacement on multi-line blocks (PowerShell Replace, python str.replace, sed multi-line)
- For changes spanning more than 3 lines: rewrite the ENTIRE method or ENTIRE file
- If an edit fails on first attempt: rewrite the whole file, don't debug whitespace
- Maximum 2 attempts for any single file edit. If both fail — rewrite the file from scratch.
- NEVER write PowerShell scripts. Use only: Read/Write tool, or bash -c with simple commands.

## Anti-loop rule
- If the same operation (edit, search, build) fails twice with similar errors: STOP and explain the problem to me. Do NOT retry more than twice.
