# Kuber Task Board

This is the active Kuber version of the supplied project-bot task board.

## Phase 0 — Repository audit

- [x] Map Kuber and upstream source modules
- [x] Map the seven analysts, GEX/Gamma Flip and broker boundaries
- [x] Produce the evidence-backed REUSE / MODIFY / NEW report

## Bot and architecture foundation

- [x] Re-read all 14 supplied development-bot contracts and specifications
- [x] Separate 14 development bots from 7 runtime market analysts
- [x] Add machine-readable ownership and workflow contracts
- [x] Enforce INSPECT -> PLAN -> IMPLEMENT -> TEST -> REVIEW -> FIX -> RETEST -> FINAL_VALIDATE -> RELEASE
- [x] Restore the documented Android -> FastAPI -> authoritative intelligence boundary

## Phase 1 — Authoritative backend core

- [ ] Verify/reuse upstream market-intelligence and GEX implementation
- [ ] Verify complete seven-agent debate, risk-veto and three-plan flow
- [ ] Expose authenticated mobile REST/WebSocket contracts through FastAPI
- [ ] Verify broker abstraction and mock/paper lifecycle against upstream source
- [ ] Verify and implement current Angel One and Fyers SDK adapters
- [ ] Verify and integrate Zerodha option-chain and live quote data
- [ ] Add mobile quote streaming/reconnect with freshness state
- [ ] Add equivalent Angel One and Fyers data builders/streams

## Phase 2 — Android API client

- [x] Kotlin / Compose foundation
- [ ] Home, AI Analysis, Gamma, Options and Trade Plan backed by API schemas
- [ ] Portfolio, Alerts, Broker and Settings backed by API schemas
- [ ] REST/WebSocket loading, error, freshness and reconnect states
- [ ] Android unit, UI and lifecycle tests

## Phase 3 — Verification

- [x] Unit, GEX, agent-schema, risk-gate, API and mock-order tests
- [x] Zerodha OAuth boundary tests with an injected fake Kite client
- [ ] Backend-to-Android contract and device verification
- [ ] Direct-mobile Zerodha option-chain and reconnect contract tests
- [ ] Angel One and Fyers adapter acceptance tests against current provider requirements
- [ ] Android device/UI acceptance tests
- [ ] Paper-trading user acceptance

## Phase 4 — Release

- [ ] Release candidate security review
- [ ] User acceptance
- [ ] Explicit live-trading gate review and approval

Live execution remains disabled until backend, broker, risk, integration,
security and explicit user-approval gates all pass. Unchecked items must never
be presented as completed functionality.
