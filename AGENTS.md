# Codex instructions for this repo

## Agent logs and readme
- Before performing any task first update memory from AGENT-LOG.md file to understand what had been done earlier.
- After performing any task briefly log changes made to the code in AGENT-LOG.md
- After making any changes to the code update TECHNICAL_SPEC.md and README.md
- TECHNICAL_SPEC.md should be developers oriented, and should contain a description of all objects and methods and interactions between them. 
- README.md should be user oriented information about application (how to use, how to configure, what functionality does it provide)

## How to run tests
- Unit tests (default): `./mvnw -B test`
- Cover all the logic with as many testes as possible.
- Unit tests must not call VEZHA/Telegram/Database or require live services.
- Run tests after each code change (not just on request).
- If testes ended with exception you should fall to this algorithm: 
  - Review exceptions
  - Rewrite code
  - Run testes
  - If fails, repeat
  - If succeeds, commit

## Config files
- create config.json file that in production should be stored in the same folder as .jar file and contain all the credentials and configurations.
- Add config.json to gitignore. Instead of it use config.json.example which should contain all the config fields and short notes on how to fill them. 
- Do not commit real tokens to the repo. Only commit config.json.example.
