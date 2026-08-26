# India Trade CLI → Kuber Android Source Mapping

Status: **baseline audit complete; Android parity implementation must not begin
until the server contract and acceptance tests below are approved.**

Reference frozen locally from `hopit-ai/india-trade-cli` commit `e62ac86`.
This mapping follows the supplied INDIA TRADE AI Android Project Notebook V2.
The reference repository remains the behavioural source of truth; it is not
copied wholesale into Android because it is a Python service/CLI.

## Authority boundary

```text
Android Compose application (control, visualization, secure session UI)
  -> authenticated API
FastAPI / India Trade service (market normalizer, shared intelligence,
  seven agents, debate, risk, paper/live execution, audit)
  -> interchangeable broker adapters
```

Android must never host raw broker secrets or independently calculate a second
GEX value. It renders the timestamped, validated snapshot supplied by the
service and submits only explicitly confirmed orders.

## Required pipeline mapping

| Notebook stage | India Trade CLI source | Kuber destination | Status |
| --- | --- | --- | --- |
| Shared quote/options snapshot | `brokers/base.py`, `market/options.py`, `market/quotes.py` | FastAPI `market` service + Android DTO | Not mapped |
| Normalize / validate option chain | `market/options.py`, broker adapters | server normalizer and contract tests | Not mapped |
| Greeks, GEX, Gamma Flip, walls | `analysis/gex.py`, `analysis/options.py` | server shared-intelligence service; Gamma screen DTO | Partial Kotlin prototype is not authoritative |
| Seven independent agents | `agent/multi_agent.py` classes at Technical/Fundamental/Options/NewsMacro/Sentiment/SectorRotation/Risk | server `agent` orchestration; Android analyst cards | Not mapped |
| Schema validation / scorecard / conflicts | `agent/schemas.py`, `agent/multi_agent.py` | server result schema and Android renderer | Not mapped |
| Bull/Bear/rebuttals/facilitator/fund manager | `agent/multi_agent.py`, `agent/deep_agent.py`, `agent/dag_orchestrator.py` | server debate stream; Android progress/results | Not mapped |
| Final risk veto and three plans | `engine/risk_gate.py`, `engine/risk_limits.py`, `engine/position_sizer.py`, `engine/trade_executor.py` | server risk/execution services; Android confirmation UI | Not mapped |
| Paper/live gate, idempotency, audit | `engine/paper.py`, `engine/paper_execute.py`, `engine/audit.py`, broker order code | server order APIs; Android order review | Not mapped |

## Reference module inventory

| India Trade CLI source | Existing capability | Required Kuber destination |
| --- | --- | --- |
| `agent/multi_agent.py` | Seven analyst implementations, weighted scorecard, conflicts and debate context | server agent package and structured analysis API |
| `agent/schemas.py`, `agent/prompts.py`, `agent/tools.py` | Structured LLM contracts, prompts, tool registry | server-only LLM/tool layer |
| `agent/deep_agent.py`, `agent/dag_orchestrator.py`, `agent/harness.py` | Deep analysis, orchestration and failure handling | server orchestration and SSE events |
| `analysis/gex.py`, `analysis/options.py`, `analysis/technical.py`, `analysis/fundamental.py` | GEX, Gamma Flip, options, technical and fundamental analysis | server analysis package; Android read-only display models |
| `analysis/volatility_surface.py`, `analysis/multi_timeframe.py`, `analysis/dcf.py`, `analysis/ml_analyst.py` | Advanced analysis inputs | planned server capabilities; not silently omitted |
| `market/options.py`, `market/quotes.py`, `market/websocket.py` | Chains, quotes, PCR, streaming/reconnect | server market-data service; Android streaming client |
| `market/news.py`, `flow_intel.py`, `macro.py`, `earnings.py`, `bulk_deals.py`, `sentiment.py` | News, flows, macro, events and sentiment inputs | server market-context service |
| `brokers/base.py` | Normalized broker contract | Kuber server `BaseBroker` contract matching all listed methods |
| `brokers/angelone.py`, `zerodha.py`, `fyers.py`, `mock.py` | Required primary adapters and safe mock route | Kuber server adapters; controlled provider tests |
| `engine/backtest*.py` | Backtest, walk-forward, cache and report | server backtest API + Android Backtest screen |
| `engine/alerts.py`, `memory.py`, `portfolio.py`, `risk_metrics.py` | Alerts, memory, portfolio and risk reports | server APIs + Android screens |
| `engine/strategy*.py`, `patterns.py`, `pairs.py`, `greeks_manager.py` | Strategies and advanced research tools | later parity backlog, explicitly tracked |
| `web/api.py`, `web/skills.py`, `web/sse.py` | OAuth, APIs, skills and real-time streaming | FastAPI contract, Android Retrofit/Ktor client and SSE client |
| `app/`, `ui/`, `macos-app/`, `bot/` | CLI, terminal UI, desktop UI and Telegram interface | replace with Android Compose; backend remains shared |

## Broker contract verification

The target server broker interface must preserve, at minimum:

`connect`, `get_quote`, `get_positions`, `get_holdings`, `get_funds`,
`place_order`, `modify_order`, `cancel_order`, `get_order_status`, and
`stream_quotes`.

Required adapters are Mock/Paper, Angel One, Zerodha and Fyers. The reference
also includes Groww, Dhan and Upstox; they are optional parity extensions, not
substitutes for the notebook's required four.

## Android endpoint mapping

| Android capability | Required server contract |
| --- | --- |
| Home/watchlist/regime | quote, candles, current GEX, alerts and P&L |
| AI Analysis | create analysis, poll/result and SSE progress stream |
| Gamma | validated GEX snapshot, expiry selection and IV smile |
| Options | normalized chain, OI/volume/IV/Greeks/PCR/anomalies |
| Trade Plan | three risk-profile plans and paper-order preview |
| Portfolio | holdings, positions, funds, P&L, exposure |
| Backtest | strategy/date/capital/cost request and result/report |
| Alerts | create/list/remove/check price/GEX/options/AI alerts |
| Broker | broker availability, role, secure OAuth connection status |
| Live Order | server-side confirmation, idempotency key, audit and kill switch |

## Mandatory acceptance gates before APK generation

1. Run India Trade CLI baseline tests and record the exact passing commit.
2. Execute its no-broker smoke command and preserve expected structured output.
3. Add parity fixtures for option chain, GEX/Gamma Flip, seven-agent result,
   conflicts, three plans, paper order and rejected live order.
4. Implement the FastAPI contract and integration tests before Compose screens.
5. Test Mock/Paper end to end; test each real broker only with its authorised
   sandbox/controlled environment.
6. Verify stale data, provider failure, reconnect, duplicate order and risk
   rejection are visible and fail closed.
7. Perform Android device/emulator acceptance tests against the tested service.
8. Only then build and sign an APK.

## Current Kuber assessment

The existing Android modules are a prototype and do not provide full India
Trade CLI parity. In particular, no authoritative FastAPI service, full agent
tool/LLM implementation, real streaming, backtesting, alerts, memory, or
complete Angel One/Fyers adapters have been validated. They must not be
represented as a complete conversion.
