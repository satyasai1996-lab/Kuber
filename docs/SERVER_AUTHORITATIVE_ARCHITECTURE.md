# Kuber Server-Authoritative Architecture

Status: **Architecture source of truth**  
Decision date: 2026-08-26  
Owner: System Architect Bot

> **Supersession notice:** This document supersedes
> `docs/MOBILE_LOCAL_ARCHITECTURE.md`. The mobile-local design must not be used
> for the production application. Android is a client; authoritative market
> intelligence, Greeks, GEX, analyst decisions, risk decisions and order
> execution remain in the Python/FastAPI service.

## 1. Architectural decision

The required production flow is:

```text
Android client
  -> authenticated FastAPI REST/WebSocket API
  -> provider market data
  -> normalized market data
  -> one immutable, validated and timestamped MarketIntelligence/GEXSnapshot
  -> seven independent analysts
  -> analyst schema validation
  -> weighted scorecard and conflict detection
  -> Bull case
  -> Bear case
  -> Bull rebuttal
  -> Bear rebuttal
  -> Facilitator
  -> Fund Manager synthesis
  -> final Risk Manager veto
  -> Aggressive / Neutral / Conservative plans
  -> paper/live execution gate
  -> broker adapter
```

The seventh Risk Analyst supplies independent evidence to the scorecard. The
final Risk Manager is a separate post-synthesis gate with absolute veto
authority.

Android may format and visualize server results. It must not independently
calculate authoritative GEX, Greeks, confidence, position size, order risk or
execution eligibility.

## 2. Current-source evidence and required corrections

The current Python code is the implementation foundation:

- `src/kuber/market/intelligence.py` already owns the shared snapshot.
- `src/kuber/market/gex.py` follows the upstream call-positive/put-negative GEX
  convention and provides a basic Gamma Flip interpolation.
- `src/kuber/agents/coordinator.py` runs exactly seven analysts concurrently.
- `src/kuber/risk/engine.py` provides a staleness-aware veto foundation.
- `src/kuber/api/app.py` already exposes initial market, analysis, broker,
  portfolio, alert, order and WebSocket routes.
- The upstream `india-trade-cli` contains fuller analyst, debate, broker,
  exchange and reconnect behavior that must be reused or modified only after
  tests establish its behavior.

The existing implementation is not yet production-authoritative:

- `/analysis/analyze` accepts caller-supplied quotes, option chains and scalar
  analyst inputs. Production analysis must fetch provider data server-side.
- Authentication is an optional global static bearer token rather than
  per-user authentication and authorization.
- Analyses, connections, stream state and audit information are primarily
  process-local.
- The current debate lacks two rebuttal rounds and a distinct Fund Manager.
- Several analysts are simplified deterministic placeholders.
- `BaseBroker` lacks `connect`, `modify_order`, `cancel_order`,
  `get_order_status` and `stream_quotes`.
- The current WebSocket is one-symbol quote fan-out with unbounded queues,
  minimal states and an optional query-string token.
- The mobile-local Android modules introduced after the original client build
  conflict with this decision. They must not be production authorities.

## 3. Ownership and module boundaries

### 3.1 Python/FastAPI owns authority

```text
src/kuber/
  api/
    app.py
    dependencies.py
    errors.py
    routers/
      auth.py
      instruments.py
      market.py
      analysis.py
      brokers.py
      portfolio.py
      orders.py
      backtest.py
      alerts.py
      stream.py
    schemas/
      common.py
      auth.py
      instruments.py
      market.py
      analysis.py
      brokers.py
      orders.py

  auth/
    service.py
    tokens.py
    authorization.py

  instruments/
    models.py
    catalog.py
    search.py
    sync.py
    repositories.py

  market/
    providers/
    normalizer.py
    option_chain.py
    greeks.py
    gex.py
    intelligence.py
    freshness.py
    streaming.py

  agents/
    contracts.py
    technical.py
    fundamental.py
    options.py
    news_macro.py
    sentiment.py
    sector_rotation.py
    risk_analyst.py
    validation.py
    scorecard.py
    conflicts.py
    debate.py
    fund_manager.py
    coordinator.py

  risk/
    engine.py
    limits.py
    position_sizing.py
    exposure.py

  brokers/
    base.py
    mock.py
    zerodha/
    angel_one/
    fyers/

  execution/
    service.py
    idempotency.py
    confirmation.py
    audit.py

  persistence/
    database.py
    repositories/
```

### 3.2 Android owns presentation and interaction

```text
android/app/src/main/java/ai/kuber/app/
  data/api/
  data/ws/
  data/auth/
  data/repository/
  domain/model/
  domain/usecase/
  ui/home/
  ui/search/
  ui/analysis/
  ui/gamma/
  ui/options/
  ui/tradeplan/
  ui/portfolio/
  ui/backtest/
  ui/alerts/
  ui/broker/
  ui/settings/
```

Android DTOs must be generated from, or contract-tested against, the OpenAPI
schema. The mobile-local `core-market`, `core-agents`, `core-risk`,
`core-broker`, `core-execution` and `core-paper` implementations must be
removed from production dependencies. Deterministic fixture code may remain
only in a clearly isolated test/demo flavor. `core-model` may remain only for
non-authoritative DTO/domain display models.

## 4. API conventions

All production routes use `/api/v1`. A successful or failed response uses a
stable envelope:

```json
{
  "schema_version": "1.0",
  "request_id": "f790a5ce-205f-480c-b3d1-49784e0e46d4",
  "server_time": "2026-08-26T12:00:00Z",
  "data": {},
  "error": null
}
```

Errors contain a stable `code`, a safe user-facing `message`, `retryable`, and
field details where applicable. Every mutation accepts or produces an
idempotency/request identifier. Dates and times are UTC ISO-8601 values.

## 5. Authentication contract

```text
POST /api/v1/auth/pkce/start
POST /api/v1/auth/token
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/auth/me
POST /api/v1/stream/tickets
```

Android uses Authorization Code with PKCE. Access tokens are short-lived;
refresh tokens rotate and are revocable. Authorization is per user and per
resource. The existing global `KUBER_API_TOKEN` can remain a development-only
mechanism but is not production mobile authentication.

A stream ticket is single-use, bound to the user/session and expires within a
short interval such as 30 seconds. Reusable bearer tokens must not appear in
WebSocket query strings, URLs or logs.

## 6. Canonical instrument catalog and search

Bare symbols are not identifiers. NSE, BSE and MCX instruments are represented
by a canonical Kuber instrument plus provider-token mappings.

```json
{
  "instrument_id": "kuber:mcx:gold:2026-10-05:fut",
  "exchange": "MCX",
  "segment": "MCX-FO",
  "tradingsymbol": "GOLD26OCTFUT",
  "display_name": "Gold October 2026 Future",
  "instrument_type": "FUT",
  "underlying": "GOLD",
  "expiry": "2026-10-05",
  "strike": null,
  "option_type": null,
  "lot_size": 1,
  "tick_size": 1.0,
  "currency": "INR",
  "catalog_version": "sha256:...",
  "as_of": "2026-08-26T00:00:00Z"
}
```

Required routes:

```text
GET  /api/v1/instruments/search
     ?q=reliance
     &exchanges=NSE,BSE,MCX
     &types=EQ,INDEX,FUT,CE,PE
     &limit=25
     &cursor=...

GET  /api/v1/instruments/{instrument_id}
GET  /api/v1/instruments/{instrument_id}/expiries
GET  /api/v1/instruments/{instrument_id}/contracts
POST /api/v1/admin/instruments/sync
```

Provider instrument masters are imported into a normalized database using an
atomic catalog version/checksum. Search supports exchange, segment and type
filters, prefix/fuzzy matching and cursor pagination. Provider tokens stay in
mapping tables. `RELIANCE` on NSE and BSE must remain two explicit choices;
the backend may never silently choose one. MCX derivatives retain expiry, lot,
tick and contract metadata.

Catalog synchronization is NEW. Existing Zerodha, Angel One and Fyers source
code provides provider-specific instrument evidence to MODIFY and normalize.

## 7. Market-intelligence REST contract

```text
POST /api/v1/market/quotes
GET  /api/v1/market/candles/{instrument_id}
GET  /api/v1/options/chain/{underlying_id}?expiry=...
GET  /api/v1/analysis/gex/{underlying_id}?expiry=...
GET  /api/v1/market/intelligence/{instrument_id}
```

`MarketIntelligence` contains:

- `snapshot_id` and immutable `input_version`/content hash;
- canonical `instrument_id`;
- provider/source and catalog version;
- provider and server timestamps;
- freshness state and rejection reasons;
- quote, candles, VWAP and volume;
- validated option contracts;
- IV, Greeks, PCR and anomaly context;
- exactly one validated `GEXSnapshot`;
- news/macro, sentiment and sector inputs with availability/provenance.

The backend validates symbol/contract identity, strike, expiry, OI, IV, gamma,
lot size and source before publication. It calculates GEX once and passes the
same snapshot to every analyst and final decision stage. Android cannot submit
arbitrary market data to a production analysis route.

## 8. Analysis REST contract

```text
POST   /api/v1/analyses
GET    /api/v1/analyses/{analysis_id}
GET    /api/v1/analyses/latest?instrument_id=...
DELETE /api/v1/analyses/{analysis_id}
```

Request:

```json
{
  "instrument_id": "kuber:nse:nifty-50:index",
  "mode": "STANDARD",
  "selected_expiries": ["2026-08-27"],
  "risk_profile_id": "default"
}
```

The service returns `202 Accepted` with `analysis_id`, `snapshot_id`, status
URL and stream channel. The final result contains:

- exactly seven structured `AgentResult` objects;
- availability and schema-validation state for each analyst;
- weighted scorecard, agreement, conflicts and weak evidence;
- Bull and Bear arguments;
- Bull and Bear rebuttals;
- Facilitator synthesis and winner;
- distinct Fund Manager decision;
- final Risk Manager decision and veto reasons;
- exactly three plans: Aggressive, Neutral and Conservative;
- entry, stop, targets, quantity, estimated maximum loss, reward/risk and GEX
  support/opposition/neutrality;
- identical `snapshot_id` and `input_version` through every stage.

One analyst failure produces `UNAVAILABLE` with a reason. It must not fabricate
evidence or cancel the other six.

## 9. Broker contract and routes

Every broker adapter implements:

```text
connect
get_quote
get_options_chain
get_positions
get_holdings
get_funds
place_order
modify_order
cancel_order
get_order_status
stream_quotes
```

Routes:

```text
GET    /api/v1/brokers
POST   /api/v1/brokers/{broker}/auth/start
GET    /api/v1/brokers/{broker}/callback
GET    /api/v1/brokers/{broker}/connection
DELETE /api/v1/brokers/{broker}/connection
GET    /api/v1/brokers/{broker}/funds
GET    /api/v1/brokers/{broker}/positions
GET    /api/v1/brokers/{broker}/holdings
GET    /api/v1/brokers/{broker}/orders/{order_id}
POST   /api/v1/brokers/{broker}/orders/{order_id}/modify
POST   /api/v1/brokers/{broker}/orders/{order_id}/cancel
```

OAuth redirects terminate on the HTTPS backend. Android opens the broker page
in a browser Custom Tab and receives only a Kuber app-link status. Broker
request/access tokens must not appear in the Android callback URL.

Mock/Paper, Zerodha, Angel One and Fyers pass the same conformance suite before
being called complete. Adapter existence in the upstream repository is not a
production-readiness claim.

## 10. Portfolio and execution contract

```text
GET  /api/v1/portfolio
POST /api/v1/orders/paper
POST /api/v1/orders/live/intents
GET  /api/v1/orders/live/intents/{intent_id}
POST /api/v1/orders/live/intents/{intent_id}/confirm
POST /api/v1/orders/{order_id}/cancel
POST /api/v1/orders/{order_id}/modify
GET  /api/v1/orders/{order_id}
GET  /api/v1/audit/events
```

A boolean `confirmed: true` is insufficient. Creating a live intent re-runs
freshness, exposure, position sizing and risk checks and returns an immutable
preview plus an expiring confirmation token/hash. Confirmation is bound to the
exact user, broker, instrument, side, quantity, order type, price, stop,
analysis ID and snapshot version. Any change or timeout requires a new preview.

Persistent idempotency prevents a duplicate order after timeout, retry or
server restart. Every rejection, confirmation, request and broker response is
audited. Paper is the default. The AI coordinator cannot invoke a live broker
outside this execution gate.

## 11. Backtest and alert routes

```text
POST   /api/v1/backtests
GET    /api/v1/backtests/{backtest_id}
GET    /api/v1/alerts
POST   /api/v1/alerts
PATCH  /api/v1/alerts/{alert_id}
DELETE /api/v1/alerts/{alert_id}
```

Production backtests identify instrument, timeframe, date range, capital,
costs and strategy/version. The backend owns historical data and enforces
no-lookahead behavior. Client-supplied candles/signals are allowed only in
explicit fixture/test APIs, never as production evidence.

## 12. WebSocket contract

Use one multiplexed endpoint:

```text
WSS /api/v1/stream?ticket=<single-use-ticket>
```

Subscription request:

```json
{
  "type": "subscribe",
  "channels": [
    "quotes",
    "market_intelligence",
    "analysis",
    "orders",
    "portfolio"
  ],
  "instrument_ids": ["kuber:nse:nifty-50:index"],
  "analysis_ids": []
}
```

Event envelope:

```json
{
  "schema_version": "1.0",
  "type": "quote",
  "event_id": "92292f2c-4869-4dbe-9e53-cf6335658074",
  "stream_id": "1cf85cce-005b-4418-b2f9-7844551a40ed",
  "sequence": 42,
  "emitted_at": "2026-08-26T12:00:00Z",
  "data": {}
}
```

Required event types:

- `hello`
- `subscription_ack`
- `snapshot`
- `quote`
- `market_intelligence`
- `analysis_progress`
- `agent_result`
- `analysis_complete`
- `order_update`
- `portfolio_update`
- `heartbeat`
- `warning`
- `error`
- `reauth_required`

Android connection states:

```text
DISCONNECTED
  -> CONNECTING
  -> AUTHENTICATING
  -> SUBSCRIBING
  -> LIVE
  -> DEGRADED or STALE
  -> RECONNECT_WAIT
  -> CONNECTING
```

Intervention/terminal states are `REAUTH_REQUIRED`, `FAILED` and `CLOSED`.

The protocol requires:

- heartbeat about every 15 seconds;
- stale state after the configured missed-heartbeat/provider threshold;
- exponential reconnect with jitter, capped around 30 seconds;
- sequence-gap detection and REST snapshot resynchronization;
- network-aware and lifecycle-safe reconnect;
- bounded per-subscriber queues;
- latest-value coalescing for quotes under load;
- no silent dropping of analysis, risk or order events;
- explicit freshness/reconnect UI.

The upstream Fyers stream offers reconnect concepts to MODIFY. Kuber still
needs a provider-neutral broker-ingestion layer and a separate authenticated
Android fan-out layer.

## 13. Security and secret boundaries

Backend secret manager/environment only:

- Zerodha, Angel One and Fyers app/API secrets;
- broker access/refresh tokens, encrypted per user;
- OpenAI API key;
- token signing and data-encryption keys;
- callback, public HTTPS and static-egress configuration.

Android may hold only Kuber access/refresh credentials using Android
Keystore-backed protection. It must never receive broker API secrets, broker
access tokens or the OpenAI key.

Production requires HTTPS/WSS, certificate validation, no cleartext Android
network configuration, redacted structured logging, state/nonce/PKCE replay
protection, per-user authorization, rate limits and audit correlation IDs.

If a broker requires raw API secrets or TOTP instead of broker-hosted OAuth,
those values must be configured at the backend boundary. “Enter the broker API
secret directly in Android” conflicts with the requirement that broker secrets
never enter Android and is not part of this architecture.

## 14. Implementation order and bot ownership

1. **Project Manager:** freeze the corrected target and reconcile existing
   mobile-local commits and in-progress Android changes.
2. **Repository Analyst:** update the evidence-based REUSE/MODIFY/NEW map.
3. **System Architect:** approve this document, OpenAPI schemas, canonical
   instrument model and WebSocket protocol.
4. **Backend Engineer:** implement versioned routers, persistence, user
   authentication and authorization.
5. **GEX Engineer:** implement validated shared snapshots and prove parity with
   deterministic upstream fixtures.
6. **Options Engineer:** implement chain validation, Greeks, IV, PCR, anomalies
   and GEX dependencies.
7. **AI Orchestrator:** implement all seven results, schema validation,
   scorecard, conflicts, full debate, Fund Manager and three plans.
8. **Broker Engineer:** expand the common interface, catalog imports and Mock,
   Zerodha, Angel One and Fyers adapters.
9. **Android Engineer:** implement authenticated REST, the WebSocket state
   machine and server-driven screens; remove local authority.
10. **Integration Engineer:** verify Android -> API -> intelligence -> analysts
    -> risk -> broker end to end.
11. **QA/Test Engineer:** run unit, contract, API, integration and device tests.
12. **Trading Safety Engineer:** adversarially test staleness, risk,
    idempotency, confirmation, mode and broker failures.
13. **Security Auditor:** validate authentication, authorization, secret
    handling, TLS, logging, storage and callbacks.
14. **Release Manager:** release only after all prior gates and explicit user
    approval.

The operational lifecycle is:

```text
INSPECT -> PLAN -> IMPLEMENT -> TEST -> REVIEW -> FIX -> RETEST
        -> FINAL_VALIDATE -> RELEASE
```

A failure returns to its owning implementation bot and then repeats QA, safety
and security validation.

## 15. Acceptance gates

### Architecture and API

- Production Android contains no authoritative local market, GEX, agent, risk
  or execution path.
- OpenAPI schema snapshots and Android DTO deserialization fixtures pass.
- Every production route is versioned and all non-public resources enforce
  per-user authentication and authorization.
- Android cannot submit arbitrary quote, chain or analyst-score data as a
  production analysis.

### Instruments and market intelligence

- Search covers NSE, BSE and MCX with unambiguous canonical IDs.
- NSE and BSE listings with the same symbol remain distinct.
- MCX contracts retain segment, expiry, lot and tick metadata.
- Catalog import is atomic, versioned and exposes stale/source state.
- Invalid/mixed/stale option-chain data is rejected before GEX publication.
- All seven analysts, debate, Fund Manager, risk and plans reference the exact
  same `snapshot_id` and `input_version`.
- Deterministic GEX, expiry aggregation and Gamma Flip fixtures pass.

### Analysts and risk

- Exactly seven independently validated analyst results are returned.
- Partial analyst failure becomes `UNAVAILABLE` without fabricated evidence or
  cancellation of other analysts.
- Scorecard, conflicts, Bull, Bear, both rebuttals, Facilitator and Fund Manager
  stages execute in the documented order.
- Exactly three plans contain entry, stop, targets, quantity, maximum loss,
  reward/risk and explicit GEX context.
- Final Risk Manager can veto every plan and order.

### Brokers and execution

- All four adapters pass the same connect/quote/portfolio/order/status/stream
  conformance suite.
- Paper order lifecycle is persistent, audited and idempotent.
- Live confirmation is exact-order-bound, expiring, audited and duplicate-safe.
- Stale data, missing stop, invalid quantity, exceeded exposure, wrong broker,
  wrong mode, missing confirmation and expired sessions block execution.
- Paper acceptance passes before any live test.

### Streaming and Android

- WebSocket authentication never puts reusable credentials in URLs or logs.
- Heartbeat, reconnect, sequence-gap recovery, resubscription and backpressure
  tests pass.
- Android visibly handles loading, empty, unavailable, stale, degraded,
  reconnecting, reauthentication and broker-error states.
- Device/instrumentation tests cover navigation, broker login callback, GEX,
  seven-agent progress, plans and live confirmation preview.

### Security and release

- APK, source, logs, URLs, database and crash-report scans contain no broker or
  OpenAI secrets.
- OAuth state/nonce/PKCE replay, authorization isolation and token-revocation
  tests pass.
- Security review, paper acceptance, rollback plan and release notes are
  complete.
- Live remains disabled until broker-specific controlled validation and
  explicit user approval.

