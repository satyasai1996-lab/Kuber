# Kuber Agent Operating Contract

Kuber is built under the 14 development-bot contracts in `project_bots/`.
These development bots are separate from the seven market-analysis agents in
`src/kuber/agents/`.

## Required development sequence

`INSPECT -> PLAN -> IMPLEMENT -> TEST -> REVIEW -> FIX -> RETEST -> FINAL_VALIDATE -> RELEASE`

No stage may be skipped. Every handoff must identify its owner, changed
artifacts, evidence, acceptance checks and unresolved blockers.

## Architecture source of truth

`Android -> FastAPI/mobile API -> normalized market data -> shared market
intelligence/GEX snapshot -> seven analysts -> debate/synthesis -> risk manager
-> three plans -> paper/live execution gate -> broker adapter`

- Reuse the India Trade CLI implementation wherever evidence supports reuse.
- Classify work as `REUSE`, `MODIFY` or `NEW`.
- Android is a client. Authoritative GEX, Greeks, risk and order decisions stay
  behind the API boundary.
- Never place broker or AI-provider secrets in Android, source, logs or APKs.
- Paper mode is the default. Live release requires tests, security review,
  paper acceptance and explicit user approval.
- The Risk Manager may veto any trade. The Release Manager cannot override it.

Run `python -m kuber.devbots.cli validate` and the test suite before presenting
a bot-architecture change as complete.
