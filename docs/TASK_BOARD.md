# Kuber Task Board

This is the active Kuber version of the supplied project-bot task board.

## Phase 0 — Repository audit

- [x] Map Kuber and upstream source modules
- [x] Map the seven analysts, GEX/Gamma Flip and broker boundaries
- [x] Produce the evidence-backed REUSE / MODIFY / NEW report

## Corrective release status

- [x] Confirm the prior APK is a remote-client shell and reject it as a release candidate
- [x] Re-read the supplied notebook and all 14 project-bot contracts
- [x] Cross-check upstream GEX, seven-agent, broker and execution source
- [x] Approve the mobile-local deployment decision in `MOBILE_LOCAL_ARCHITECTURE.md`
- [x] Remove every laptop/FastAPI dependency from the Android runtime

## Phase 1 — Authoritative core (inside Android)

- [x] Shared market-intelligence and timestamped GEX snapshot schema
- [x] Seven-agent scorecard, debate, risk veto and paper execution
- [x] Broker abstraction and mock/paper lifecycle
- [x] Session-only direct Zerodha personal-app connection; no credential persistence
- [x] No-account fixture-backed paper demo session
- [ ] Verify and implement current Angel One and Fyers SDK adapters
- [x] Add validated Zerodha option-chain builder from instrument and live quote data
- [ ] Add mobile quote streaming/reconnect with freshness state
- [ ] Add equivalent Angel One and Fyers data builders/streams

## Phase 2 — Android presentation

- [x] Kotlin / Compose foundation
- [x] Mobile-local Home, AI Analysis, Gamma, Options and Trade Plan screens
- [x] Local Portfolio, Broker and Settings screens
- [x] Remove fake endpoint input and remote Retrofit dependency from the app flow
- [ ] Android unit, UI and lifecycle tests

## Phase 3 — Verification

- [x] Unit, GEX, agent-schema, risk-gate, API and mock-order tests
- [x] Zerodha OAuth boundary tests with an injected fake Kite client
- [x] Corrective mobile-local debug APK compilation
- [ ] Direct-mobile Zerodha option-chain and reconnect contract tests
- [ ] Angel One and Fyers adapter acceptance tests against current provider requirements
- [ ] Android device/UI acceptance tests
- [ ] Paper-trading user acceptance

## Phase 4 — Release

- [ ] Release candidate security review
- [ ] User acceptance
- [ ] Explicit live-trading gate review and approval

Live execution is direct-to-Kite but guarded by a fresh live snapshot, final
Risk Manager decision, immutable review hash, and typed `LIVE` confirmation.
The unchecked items are deliberate release gates, not silently assumed
functionality.
