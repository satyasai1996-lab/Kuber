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

- [x] Canonical provider-backed NSE/BSE/MCX instrument catalogue and versioned search API
- [x] Implement strict atomic Zerodha master synchronization for NSE/NFO/BSE/BFO/MCX
- [ ] Run a controlled live-account master sync and persist the active catalogue version
- [x] Match upstream Gamma Flip semantics and enforce decimal IV units with regression goldens
- [ ] Verify the remaining upstream market-intelligence calculations and fixtures
- [ ] Verify complete seven-agent debate, risk-veto and three-plan flow
- [ ] Expose authenticated mobile REST/WebSocket contracts through FastAPI
- [ ] Verify broker abstraction and mock/paper lifecycle against upstream source
- [ ] Verify and implement current Angel One and Fyers SDK adapters
- [ ] Verify and integrate Zerodha option-chain and live quote data
- [ ] Add mobile quote streaming/reconnect with freshness state
- [ ] Add equivalent Angel One and Fyers data builders/streams

## Phase 2 — Android API client

- [x] Search UI for NIFTY/SENSEX/MCX instrument families with explicit exchange/segment labels
- [x] Connect search to `/api/v1/instruments/search`, retaining explicit no-price shortcuts only when no backend is configured
- [x] Kotlin / Compose foundation
- [ ] Home, AI Analysis, Gamma, Options and Trade Plan backed by API schemas
- [ ] Portfolio, Alerts, Broker and Settings backed by API schemas
- [ ] REST/WebSocket loading, error, freshness and reconnect states
- [x] Android instrument-search client/repository unit tests, including error and stale-response handling
- [ ] Android UI and lifecycle tests

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
