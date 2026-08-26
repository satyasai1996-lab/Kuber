# Kuber Task Board

This is the active Kuber version of the supplied project-bot task board.

## Phase 0 — Repository audit

- [x] Map Kuber and upstream source modules
- [x] Map the seven analysts, GEX/Gamma Flip and broker boundaries
- [x] Produce the evidence-backed REUSE / MODIFY / NEW report

## Phase 1 — Backend

- [x] Shared market-intelligence and timestamped GEX snapshot schema
- [x] Seven-agent scorecard, debate, risk veto and paper execution
- [x] Broker abstraction and mock/paper lifecycle
- [x] Real Zerodha backend OAuth boundary (requires user deployment configuration)
- [x] No-account fixture-backed paper demo session
- [ ] Verify and implement current Angel One and Fyers SDK adapters
- [ ] Add validated real option-chain builders per provider
- [ ] Add authenticated streaming contracts and reconnect tests

## Phase 2 — Android

- [x] Kotlin / Compose foundation
- [x] API-driven Home, AI Analysis, Gamma, Options and Trade Plan screens
- [x] Portfolio, Alerts and Broker screens
- [x] Production OAuth onboarding with paper-demo fallback
- [ ] Android UI / device tests and API-token storage through Android Keystore
- [ ] Real-time stream presentation after backend streaming is verified

## Phase 3 — Verification

- [x] Unit, GEX, agent-schema, risk-gate, API and mock-order tests
- [x] Zerodha OAuth boundary tests with an injected fake Kite client
- [x] Debug APK compilation
- [ ] Angel One and Fyers adapter acceptance tests against current provider requirements
- [ ] Android device/UI acceptance tests
- [ ] Paper-trading user acceptance

## Phase 4 — Release

- [ ] Release candidate security review
- [ ] User acceptance
- [ ] Explicit live-trading gate review and approval

Live execution remains disabled. The unchecked items are deliberate release
gates, not silently assumed functionality.
