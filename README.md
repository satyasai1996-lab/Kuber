# Kuber

Kuber is a safety-first Indian-market trading-intelligence platform. Its Android client is the control and visualization layer; market intelligence, seven AI analysts, broker integration, risk controls, audit logging, and order execution remain on the backend.

## Architecture

```text
Android (Kotlin + Compose)
        |
FastAPI contract
        |
Market normalizer -> validated timestamped GEX snapshot -> seven AI agents
        |                         |                         |
        +-------------------------+-> debate/fund manager -> risk veto -> trade plan
                                                                    |
                                                       paper or explicitly-confirmed live order
                                                                    |
                                                Mock / Angel One / Zerodha / Fyers adapters
```

GEX is calculated once from normalized options data and then shared with every agent. The Options Analyst interprets it in detail; the Risk Manager can veto every trade. Paper trading is the only enabled execution mode in the reference implementation.

## Local development

Requires Python 3.11+.

```bash
python -m venv .venv
.venv/bin/python -m pip install -e ".[dev]"
PYTHONPATH=src .venv/bin/python -m unittest discover -s tests -v
PYTHONPATH=src .venv/bin/uvicorn kuber.api.app:app --reload
```

On Windows, use `.venv\\Scripts\\python.exe` instead.

## Project layout

- `src/kuber/market/`: provider normalization, immutable market intelligence, and GEX.
- `src/kuber/agents/`: seven independently testable analyst contracts and coordinator.
- `src/kuber/risk/`: position sizing, freshness checks, and veto decisions.
- `src/kuber/brokers/`: common broker contract and safe paper broker.
- `src/kuber/execution/`: idempotent execution gate and audit trail.
- `src/kuber/backtest/`: no-lookahead paper backtesting over validated AI-bot signals.
- `src/kuber/alerts/`: server-side alert-rule contracts.
- `src/kuber/api/`: Android-facing FastAPI contract.
- `android/`: Kotlin/Compose client skeleton and API boundary.
- `docs/source-to-android-mapping.md`: verified upstream-to-Kuber mapping.
