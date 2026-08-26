# Live Market / Broker REUSE–MODIFY–NEW Audit

Audit date: 2026-08-26
Reference checkout: `work/reference-india-trade-cli` at
`e62ac86d4a182f6df0f785c4620a527d753b4517`
Kuber checkout inspected: `work/publish` at
`6bdbbf79a926c59eb9b12144da75c3bbd51acba7` plus the uncommitted Android UI
work listed in the final section.

This is a source audit, not evidence of a real broker connection. `REUSE`
means behavior or a contract is suitable as the regression oracle. `MODIFY`
means the behavior exists upstream but must be ported or adapted to Kuber's
mobile-local runtime. `NEW` means the complete capability is absent and an
interface, enum entry, or hard-coded demo symbol does not count as completion.

## Executive finding

Kuber no longer needs FastAPI for its Android execution path:
`android/app/src/main/java/ai/kuber/app/LocalTradingRuntime.kt:47-54` constructs
the broker, market, agent, paper and execution components in-process. Its only
direct live provider implementation is currently Zerodha
(`android/core-broker/src/main/java/ai/kuber/core/broker/zerodha/ZerodhaBroker.kt`).

The current Android implementation is **not** full live-market parity:

- direct live refresh is explicitly enabled only for NIFTY and BANKNIFTY
  (`InstrumentCatalog.kt:16-25`, especially the final `true` at lines 16-17);
- the searchable BSE/BFO/MCX entries are static identifiers and demo metadata,
  not a provider-backed instrument master (`InstrumentCatalog.kt:3-25`);
- the Zerodha adapter downloads only the NFO instrument file and parses only
  CE/PE rows (`ZerodhaBroker.kt:177-181`, `214-221`);
- `streamQuotes` is an HTTPS quote poll every five seconds, and the source
  labels a binary Kite WebSocket as a separate release gate
  (`ZerodhaBroker.kt:160-174`);
- no Android Angel One or Fyers implementation exists under
  `android/core-broker/src/main/java`; only `Broker.kt` and the Zerodha adapter
  are present.

## Capability classification

| Capability | Upstream evidence | Current Kuber evidence | Class | Required disposition |
| --- | --- | --- | --- | --- |
| Normalized broker contract | `brokers/base.py:155-160` makes the rest of the platform depend on `BrokerAPI`; quote/options/account/order methods are at `198-251` | Android `Broker.kt:12-24` covers quote, chain, account, place/modify/cancel/status and stream | **REUSE** | Keep one provider-neutral contract. Provider-specific payloads must terminate inside adapters. |
| Live quote selection | `market/quotes.py:65-97` orders sources as WebSocket cache → data broker REST → labelled yfinance fallback | Zerodha direct quote mapping and Kite quote request are at `ZerodhaBroker.kt:110-115`, `183-190`; app refresh is at `LocalTradingRuntime.kt:87-94` | **MODIFY** | Preserve ordered, source-labelled acquisition, but implement it locally in Android. Delayed fallback must never be labelled live. |
| Option-chain construction | Upstream broker-first/NSE fallback is at `market/options.py:23-53`; Zerodha builds NFO instruments and quotes at `brokers/zerodha.py:190-235` | Android selects nearest NFO expiry/strikes, fetches quotes and calculates IV/gamma at `ZerodhaBroker.kt:118-139` | **MODIFY** | Retain validated nearest-expiry behavior, cache/version the master, and add provider contract fixtures. Current path is NFO-only. |
| Searchable instrument master | Upstream has no provider-neutral instrument search. `engine/search.py:42-48,173-183` searches saved analyses; `market/options_scanner.py:24-44,68-87` uses a static F&O scan list. Zerodha's instrument scan at `brokers/zerodha.py:315-324` is an internal historical-token lookup | `InstrumentCatalog.kt:28-48` searches ten hard-coded enum entries; Zerodha caches only NFO CE/PE instruments at `ZerodhaBroker.kt:177-181,214-221` | **NEW** | Build a provider-backed, versioned catalogue/search index. Static demo names are useful UI fixtures but are not a tradable universe. |
| NSE/NFO universe | Upstream broker instrument syntax is documented at `brokers/base.py:217-234`; live NFO chain batching is at `brokers/zerodha.py:195-219` | Android aliases NIFTY/BANKNIFTY to NSE indices at `ZerodhaBroker.kt:110-115` and loads NFO at `118-139` | **MODIFY** | Generalize beyond two index aliases and prove stock equity/F&O search, quote and chain behavior independently. |
| BSE/BFO/MCX universe | Upstream common model mentions NSE/BSE/NFO/MCX (`brokers/base.py:56,111`), while Dhan alone explicitly maps BFO and MCX (`brokers/dhan.py:42-49`) | Android lists SENSEX/BANKEX and four MCX products at `InstrumentCatalog.kt:20-25`, but its live data adapter never loads BSE, BFO or MCX. `LocalTradingRuntime.kt:153-156` merely accepts those exchange strings in an order request | **NEW** | Add real provider instrument masters, quotes, chains and contract tests before any live eligibility flag. Accepted order text is not market-data support. |
| FastAPI market endpoints | Upstream includes the skills router at `web/api.py:145`; quote/options routes are `web/skills.py:152-177`; the wider API/skills surface is enumerated at `web/skills.py:12-39` | Legacy Kuber routes include quote/refresh/stream/options at `src/kuber/api/app.py:317-378`, broker routes at `401-452` and orders at `462-476` | **MODIFY** | Retain payload semantics as local use cases/tests, not as an Android server dependency. The Python FastAPI remains a compatibility/reference surface only for the mobile-local release. |
| Streaming and reconnect | Upstream Fyers manager documents live ticks, cache and auto-reconnect at `market/websocket.py:1-30`; subscription/cache methods are at `201-252`. Browser delivery uses SSE queues/heartbeats at `web/sse.py:25-109` and endpoints at `web/api.py:1677-1723` | Android contract has connection/reconnect callbacks (`Broker.kt:24-34`), but Zerodha performs five-second REST polling (`ZerodhaBroker.kt:160-174`). Legacy Python fan-out/WebSocket is at `src/kuber/market/streaming.py:11-49` and `src/kuber/api/app.py:335-361` | **MODIFY** | Implement actual provider WebSocket framing/subscription/reconnect and lifecycle handling on Android. Do not describe polling as streaming. |
| Zerodha broker | Upstream live quote/options/order behavior is at `brokers/zerodha.py:167-286`; instrument lookup is at `315-324` | Android uses direct phone-to-Kite OAuth/session exchange at `ZerodhaBroker.kt:68-108`, quote/chain at `110-140`, account/order at `142-158` | **MODIFY** | Keep the mobile-local adapter, but add instrument-universe coverage, actual WebSocket, order-status response fixtures and controlled broker validation. |
| Angel One | Upstream adapter covers quote, chain, order and script search at `brokers/angelone.py:316-419,479-519` | No Android Angel One adapter exists. The Python `src/kuber/brokers/providers.py:59-62` is only a gateway wrapper, not a SmartAPI transport | **MODIFY** | Port only after the static-IP/auth constraints are satisfied and current SmartAPI contracts are independently tested. Until then show unavailable. |
| Fyers | Upstream adapter covers quote, chain and order at `brokers/fyers.py:443-611`; upstream WebSocket is Fyers-specific (`market/websocket.py:141-189,201-260`) | No Android Fyers adapter exists | **MODIFY** | Implement as an independent module/contract suite; do not route it through a Zerodha implementation. |
| Groww/Upstox/Dhan | Upstream has adapters in `brokers/groww.py`, `brokers/upstox.py`, `brokers/dhan.py`; session choices currently expose Groww and Upstox but not Dhan at `brokers/session.py:67-98,340-406` | No Android implementations exist | **NEW** (Kuber scope) | Optional parity work after the notebook-required brokers. Dhan's exchange map is evidence for naming only, not proof of live Kuber support. |
| Broker role routing | Upstream separates data/execution brokers at `brokers/session.py:197-245`; tests cover explicit routing at `tests/test_broker_roles.py:84-159` and `tests/test_execution_routing.py:44-85` | Android runtime owns one Zerodha instance plus Paper (`LocalTradingRuntime.kt:48-54`) | **NEW** | Add only if multi-broker operation remains in scope; otherwise document the deliberate single-live-provider constraint. |
| Paper/demo market | Upstream `brokers/mock.py:33-149` supplies demo account/order behavior but explicitly does not supply an options chain (`brokers/mock.py:101-106`) | Android builds clearly labelled deterministic quote/options fixtures at `LocalTradingRuntime.kt:72-85,167-168` | **REUSE** | Keep for offline bot/testing paths. Never blend fixtures into connected/live state. |

## Endpoint-to-local-use-case mapping

The source endpoint behavior is reusable, but Android must call local use cases
for the mobile-only deployment.

| Upstream/Kuber HTTP behavior | On-device authority | State |
| --- | --- | --- |
| Upstream `POST /skills/quote` (`web/skills.py:152-162`); Kuber `GET /market/quote` and `POST /market/refresh` (`src/kuber/api/app.py:317-333`) | `Broker.getQuote` → `MarketIntelligenceBuilder` (`Broker.kt:15`, `LocalTradingRuntime.kt:87-94,160-165`) | Implemented for direct Zerodha NIFTY/BANKNIFTY and paper fixtures |
| Upstream `POST /skills/options_chain` (`web/skills.py:169-177`); Kuber `GET /options/chain` (`src/kuber/api/app.py:363-378`) | `Broker.getOptionChain` → validation/Greeks/GEX (`Broker.kt:16`, `ZerodhaBroker.kt:118-139`) | Implemented for nearest-expiry NFO only |
| Upstream `GET /stream/prices` SSE (`web/api.py:1677-1705`); legacy Kuber WebSocket (`src/kuber/api/app.py:335-361`) | `Broker.streamQuotes` (`Broker.kt:24-34`) | Contract present; actual Kite WebSocket missing |
| Upstream OAuth pages/callbacks (`web/api.py:653-927`) and Kuber legacy Zerodha routes (`src/kuber/api/app.py:426-452`) | `ZerodhaAuth.loginUrl/exchangeRequestToken` (`ZerodhaBroker.kt:68-92`) | Direct session exchange implemented; no real-account evidence in this audit |

## Test reuse and missing evidence

### Suitable regression sources

- Upstream quote adapter behavior: Fyers normal/after-close cases at
  `tests/test_fyers_quote.py:73-176`; Angel One and Upstox OHLC/change cases at
  `tests/test_broker_quote_change.py:33-90,167-216`.
- Upstream stream behavior: delivery, fan-out, isolation, overflow and cleanup
  at `tests/test_sse_streaming.py:41-295`; endpoint registration/content type at
  `335-419`.
- Upstream broker lifecycle/routing: `tests/test_session.py:53-192`,
  `tests/test_broker_roles.py:84-186`, and
  `tests/test_execution_routing.py:44-85,91-162`.
- Existing Kuber Python fixtures cover NFO chain construction
  (`tests/test_option_chain.py:7-34`), backend WebSocket latest-value replay and
  auth (`tests/test_market_streaming.py:13-35`), and a fake Zerodha OAuth/quote
  exchange (`tests/test_zerodha_oauth.py:35-55`).
- Existing Android tests cover a fake HTTP session/quote and volatile logout
  only (`android/core-broker/src/test/java/ai/kuber/core/broker/zerodha/ZerodhaBrokerTest.kt:9-34`).
  Static search tests cover aliases/MCX labels, not live tradability
  (`android/app/src/test/java/ai/kuber/app/InstrumentCatalogTest.kt:8-25`).

### Release blockers not covered by current tests

1. No controlled real Zerodha quote, instrument-master, options-chain,
   positions, funds or order-status acceptance record exists.
2. No Android test covers the actual NFO CSV parser, quote batching, nearest
   expiry/strike selection, partial quote failures, token expiry or rate limits.
3. No actual Kite WebSocket implementation or Android reconnect/lifecycle test
   exists; five-second REST polling is the current fallback.
4. No provider-backed NSE equity search or full NSE/NFO universe index exists.
5. BSE, BFO and MCX have UI catalogue labels but no Android live data adapter or
   provider contract tests.
6. Angel One and Fyers have no Android transport/auth/market/order tests.
7. The legacy FastAPI tests do not validate the mobile-local runtime, and the
   Android fake-transport test does not establish live-provider compatibility.
8. No upstream or Kuber test proves one universal broker symbol convention
   across all five requested exchange segments; this must remain provider
   specific behind the normalized contract.

## Repository-analyst handoff

The safe implementation order for this surface is:

1. dynamic, versioned provider instrument catalogue and normalized search;
2. Zerodha NSE/NFO quote/chain contract fixtures and error states;
3. actual Kite WebSocket stream/reconnect/lifecycle implementation;
4. controlled Zerodha acceptance evidence;
5. BSE/BFO/MCX data only through a provider proven to support those segments;
6. Angel One and Fyers as independent adapters and independent test gates.

Until those gates pass, Kuber may accurately claim on-device paper workflow and
a partial direct Zerodha REST implementation. It must not claim a complete
India-wide live universe, genuine streaming, Angel One/Fyers integration, or
validated live-order readiness.
