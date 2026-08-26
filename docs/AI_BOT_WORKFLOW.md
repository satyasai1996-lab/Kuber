# Kuber AI-bot Delivery Workflow

Kuber follows the controlled workflow in the supplied India Trade AI Project
Bots folder. Development gates run sequentially: INSPECT -> PLAN -> IMPLEMENT
-> TEST -> REVIEW -> FIX -> RETEST -> RELEASE. A screen cannot claim a broker,
market or analyst capability until its owning core module and tests pass.

```text
Repository audit and mobile-local architecture approval
  -> on-device shared market intelligence / GEX snapshot
  -> seven independent analysts
  -> scorecard and conflict detection
  -> bull / bear / facilitator debate
  -> fund-manager trade plans
  -> independent risk veto
  -> paper execution
  -> test + security review
  -> explicit live-trading release gate
```

## Bot responsibilities

| Project bot | Kuber implementation boundary | Release evidence |
| --- | --- | --- |
| Repository Analyst | `docs/REUSE_MODIFY_NEW_AUDIT.md` | Source paths and a REUSE/MODIFY/NEW classification |
| System Architect | `docs/MOBILE_LOCAL_ARCHITECTURE.md`, Android core modules | One timestamped market/GEX snapshot shared by every agent |
| Core / GEX / Options bots | `core-model`, `core-market` | Unit tests for input validation, GEX and option schemas |
| AI Orchestrator | `agents/coordinator.py` | Seven independent results, scorecard, debate and risk veto |
| Broker Engineer | `core-broker`, `core-execution`, `core-paper` | Provider adapters use volatile sessions and pass controlled adapter tests |
| Android Engineer | `android/app/` | Native Compose screens consume local core contracts only; no authoritative trading math in UI |
| Integration / QA / Safety bots | `tests/` | Paper order, idempotency, stale snapshot, risk veto and API failure checks |
| Security / Release bots | configuration, docs, release review | TLS/auth/secret review, passing tests, paper acceptance and user approval before live mode |

## Non-negotiable controls

- The Android APK/persistent storage never contains broker API secrets or access
  tokens. Personal-mode inputs exist only in volatile memory for the session.
- GEX is calculated once in the on-device core from validated option data, timestamped,
  and then shared with all seven analysts.
- A connected broker is not automatically live-enabled. Paper mode is the
  default; live execution additionally needs production configuration, an
  explicit confirmation and risk approval.
- Demo data is labelled as demo data and never represented as live market data.
- `Kuber` remains the product and Android package name; the India Trade CLI is
  a reference implementation, not the app name.
