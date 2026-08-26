# Kuber — REUSE / MODIFY / NEW Audit

Audit date: 2026-08-26
Reference source: `hopit-ai/india-trade-cli` (MIT licensed) and the supplied
`India-Trade-AI-Project-Bots` control folder.

This report records what was verified in source files. It does not treat an
upstream feature as production-ready for Kuber without Kuber-specific tests and
security review.

| Capability | Upstream evidence | Kuber evidence | Classification | Action |
| --- | --- | --- | --- | --- |
| Seven-agent orchestration | `agent/multi_agent.py`, `agent/dag_orchestrator.py` | `src/kuber/agents/coordinator.py`, `default_agents.py` | REUSE | Keep Kuber’s typed seven-agent, scorecard, debate and risk-veto flow; strengthen API/UI presentation. |
| GEX and Gamma Flip | `analysis/gex.py:34-81`, `analysis/gex.py:108-160` | `src/kuber/market/gex.py`, `models.py:GexSnapshot` | REUSE | Keep the same dealer-short sign convention and interpolated flip; preserve the backend-only snapshot. |
| Common broker interface | `brokers/base.py`; provider adapters under `brokers/` | `src/kuber/brokers/base.py`, `providers.py` | MODIFY | Extend the Kuber interface for real OAuth session creation and registered provider gateways, keeping the controlled live gate. |
| Zerodha OAuth | `brokers/zerodha.py:34-71`, `web/api.py:653-694` | sandbox-only `kite_sandbox.py` | MODIFY | Replace the sandbox-first Android journey with user-owned Kite OAuth configured on the backend; retain sandbox only as an isolated developer option. |
| Fyers and Angel One | `brokers/fyers.py:207-565`, `brokers/angelone.py:52-419` | guarded interfaces only in `providers.py` | NEW | Implement only after current provider SDK/API requirements are verified; never advertise as connected before adapter tests pass. |
| Demo / no-broker start | `web/api.py:32`, `web/api.py:634` | mock broker exists after a submitted analysis | MODIFY | Add an explicit fixture-backed demo session so the Android app works before broker configuration. |
| Market-data fallback | `market/yfinance_provider.py` | normalizer accepts provider payloads but no fetcher | NEW | Add a separately labelled delayed-data adapter only after its data quality/option-chain limits are tested. It must not create a false live-data claim. |
| Paper trade lifecycle | `engine/paper_execute.py:31-139`, `brokers/mock.py` | `brokers/mock.py`, `execution/service.py` | REUSE | Keep idempotent paper execution and audit; expose it in the dashboard. |
| Web/API streaming | `web/api.py`, `web/sse.py` | REST FastAPI endpoints in `api/app.py` | MODIFY | Add streaming only after stable event schemas and reconnect tests. REST dashboard wiring comes first. |
| Android application | upstream has macOS/Electron UI, not Android | Kotlin/Compose under `android/app` | NEW | Build native Kuber screens using API contracts. Placeholder tabs are not considered complete. |
| Credentials and tokens | upstream restores sessions from local token files | `config.py`, `connection.py` avoid APK storage | MODIFY | Store provider secrets only in backend deployment secret storage. Use a temporary in-memory session for development; do not copy plaintext token-file persistence. |

## Phase 0 conclusion

Kuber’s reusable foundations are the typed market/GEX model, seven analysts,
risk gate, paper broker, no-lookahead backtest and FastAPI API. The required
work is not a rebuild: it is the missing provider onboarding, demo entry point,
market-data integration and Android data screens. Every implementation change
must be covered by the test/review/retest steps in `AI_BOT_WORKFLOW.md`.
