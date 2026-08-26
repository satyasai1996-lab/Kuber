# Kuber AI-bot Delivery Workflow

Kuber follows the controlled workflow in the supplied India Trade AI Project
Bots folder. The workflow is intentionally ordered so an Android screen cannot
claim a broker, market, or AI capability before the backend and its validation
are in place.

```text
Repository audit
  -> shared market intelligence / GEX snapshot
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
| System Architect | `src/kuber/models.py`, `market/`, `brokers/base.py` | One timestamped market/GEX snapshot shared by every agent |
| Backend / GEX / Options bots | `api/`, `market/`, `agents/` | Unit and API tests for input validation, GEX and agent schemas |
| AI Orchestrator | `agents/coordinator.py` | Seven independent results, scorecard, debate and risk veto |
| Broker Engineer | `brokers/`, `execution/` | Provider adapters use server-side credentials and pass controlled adapter tests |
| Android Engineer | `android/app/` | Native Compose screens consume API contracts only; no authoritative trading math or secrets in the APK |
| Integration / QA / Safety bots | `tests/` | Paper order, idempotency, stale snapshot, risk veto and API failure checks |
| Security / Release bots | configuration, docs, release review | TLS/auth/secret review, passing tests, paper acceptance and user approval before live mode |

## Non-negotiable controls

- The Android APK never contains broker API secrets, access tokens or a live-order bypass.
- GEX is calculated once in the backend from validated option data, timestamped,
  and then shared with all seven analysts.
- A connected broker is not automatically live-enabled. Paper mode is the
  default; live execution additionally needs production configuration, an
  explicit confirmation and risk approval.
- Demo data is labelled as demo data and never represented as live market data.
- `Kuber` remains the product and Android package name; the India Trade CLI is
  a reference implementation, not the app name.
