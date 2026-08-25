# Kuber: Source-to-Android Mapping

## Source baseline

This mapping is based on the public upstream repository [`hopit-ai/india-trade-cli`](https://github.com/hopit-ai/india-trade-cli), whose README identifies it as **Vibe Trading**. `AnkitArya/india-trade-cli` has the same README revision and is treated as a duplicate, not a separate source of truth. The Kuber project keeps its own name, package identity, API branding, and Android application identity; upstream is an implementation reference only.

The Android app is a control and visualization client. Analysis, market-data normalization, broker sessions, order execution, GEX calculation, risk checks, and audit persistence remain backend responsibilities.

## Verified source inventory

| Existing source | Verified responsibility | Android destination | Backend responsibility retained |
| --- | --- | --- | --- |
| `agent/multi_agent.py` | Seven analyst reports, weighted scorecard, conflict detection, bull/bear debate, facilitator and final recommendation | AI Analysis screen: analyst cards, scorecard, conflicts, streamed debate, final decision | Execute agents, validate structured output, manage failures, calculate scorecard and synthesis |
| `agent/deep_agent.py` | Deeper LLM-backed analysis path | AI Analysis: “Deep analysis” action and progress state | Long-running analysis orchestration and result persistence |
| `agent/tools.py` | Agent-accessible market and analysis tools | No direct client equivalent | Tool authorization and data access |
| `analysis/gex.py` | Strike-level call/put GEX, net GEX, Gamma Flip interpolation, regime classification and interpretation | Gamma screen: GEX curve, flip marker, regime chip, key-strike table and timestamp | Generate one validated, immutable GEX snapshot shared by all agents |
| `analysis/options.py` and `market/options.py` | Greeks and normalized option-chain analytics | Options screen: chain, OI, volume, IV, Greeks, PCR and anomalies | Obtain, normalize and validate options data |
| `brokers/base.py` | Broker contract for quotes, options, portfolio, orders and cancellation | Broker screen and portfolio/trade-plan data models | Common adapter interface and normalized broker responses |
| `brokers/fyers.py`, `brokers/zerodha.py`, `brokers/angelone.py` | Provider authentication, market data and execution | Broker connection/status UI only; never store raw credentials in Android | OAuth/TOTP flows, token storage, sessions and broker-specific API calls |
| `brokers/session.py` | Active-broker registration and routing | Broker selection and connection-state display | Data/execution broker routing |
| `engine/paper.py` | Paper-trading execution | Trade Plan: paper-submit flow and audit receipt | Simulated execution, idempotency and audit log |
| `engine/trader.py` | Order/trade execution workflow | Trade Plan: final order review and live-confirmation flow | Order construction, routing and broker submission |
| `web/api.py` and `web/skills.py` | FastAPI sidecar, broker/auth and skill endpoints | Retrofit/Ktor API client, auth/session state and server-sent progress handling | Versioned Android REST API and access control |
| `macos-app/.../GEXCard.jsx` | Existing GEX visualization semantics | Compose Gamma screen | None; UI reference only |
| `macos-app/.../StreamingAnalysisCard.jsx` | Streaming analysis presentation | Compose AI Analysis screen | Analysis streaming endpoint |
| `tests/test_pipeline.py`, `tests/test_options_analytics.py`, `tests/test_api_broker.py` | Pipeline, options and broker regression tests | Android contract/UI tests derived from stable payloads | Preserve and expand backend regression coverage |

## Target Android-to-backend contracts

| Android feature | Required endpoint/service | Required response properties |
| --- | --- | --- |
| Home/watchlist | `GET /market/quote/{symbol}`, `GET /market/candles/{symbol}` | price, change, timestamp, freshness/source |
| Gamma | `GET /analysis/gex/{symbol}` | symbol, spot, selected expiries, GEX-by-strike, flip, regime, walls, timestamp and source |
| Options | `GET /options/chain/{symbol}`, `GET /analysis/iv-smile/{symbol}` | expiry, CE/PE data, OI, OI delta, IV, Greeks, volume, PCR and anomalies |
| AI Analysis | `POST /analysis/analyze`, `POST /analysis/deep-analyze`, `GET /analysis/{id}` | all seven `AgentResult`s, input version/timestamps, scorecard, conflicts, debate, decision and risk status |
| Trade plan | analysis result plus `POST /orders/paper` | aggressive, neutral and conservative plans; GEX context; entry, stop, targets, size and risk rationale |
| Live order | `POST /orders/live/confirm` | explicit confirmation payload, idempotency key, risk estimate, broker response and audit ID |
| Portfolio | `GET /portfolio` | holdings, positions, P&L, margin, exposure and broker provenance |
| Broker | `GET /brokers`, `GET /broker/status` | supported broker, connection status, data/execution role, paper/live mode |
| Alerts | `GET /alerts`, `POST /alerts` | price, GEX, Gamma Flip, options and AI alert rules/status |

## Non-negotiable implementation rules

1. The backend computes GEX once per normalized, timestamped snapshot; Android and all seven agents consume that same snapshot.
2. The Options Analyst interprets GEX in depth; the Fund Manager receives both its interpretation and the raw snapshot, and declares whether GEX supports, opposes or is neutral to the trade.
3. The Risk Manager may veto every trade. No analyst or synthesis result can bypass it.
4. Paper mode is default. Live mode requires a distinct UI, explicit final confirmation, idempotency key and audit event.
5. Broker credentials and broker secrets never enter the APK. Android only handles user-session and connection status.
6. Stale or unavailable market/GEX inputs must be visible, attached to each `AgentResult`, and may block execution.

## Implementation order

1. Freeze the selected upstream commit and run its existing backend tests.
2. Define normalized `MarketIntelligence`, `GEXSnapshot`, `AgentResult`, `TradePlan`, `Order` and `AuditEvent` payloads.
3. Add a versioned Android-facing FastAPI API without bypassing existing broker/risk logic.
4. Add contract tests for all Android payloads before Compose screens.
5. Build Compose navigation, secure session storage and read-only market/Gamma/AI screens.
6. Add paper execution, then the separately gated live-confirmation flow.

## Explicit gaps to close before Android implementation

- The source currently exposes FastAPI sidecar and OpenClaw-oriented routes; it needs the notebook’s Android REST contract and authentication model.
- The source’s GEX result must be elevated into a timestamped, validated shared snapshot rather than produced independently by consumers.
- Broker routing must explicitly distinguish the data broker from the execution broker.
- Android-specific auth, session refresh, secure storage, offline/error states and API contract tests must be added.
