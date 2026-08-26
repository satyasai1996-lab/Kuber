# Kuber Mobile-Local Architecture Decision

Status: approved for implementation after cross-checking the supplied Word
notebook, project-bot control files, `hopit-ai/india-trade-cli`, and the current
Kuber Android source.

## Why this decision exists

The previous APK was a remote FastAPI client. It defaulted to
`https://api.example.com`, sent the Zerodha request token to a laptop/backend,
and discarded part of the live refresh response. That release therefore did
not meet the user's later requirement that the phone host the Kuber runtime.

The supplied notebook originally places authoritative calculations on a
backend. The user's later instruction explicitly changes the deployment
location. Kuber will preserve the same logical boundaries inside Android, but
will not require a Kuber laptop or cloud service.

## Authoritative on-device flow

```text
Compose UI
  -> local repositories/use cases
  -> broker/market provider
  -> normalization and validation
  -> one immutable MarketIntelligence + GEXSnapshot
  -> seven independent analyst engines
  -> schema validation
  -> weighted scorecard and conflict detection
  -> Bull case -> Bear case -> Bull rebuttal -> Bear rebuttal
  -> facilitator synthesis
  -> fund-manager decision with explicit GEX alignment
  -> final Risk Gate with veto authority
  -> aggressive / neutral / conservative plans
  -> paper execution or separately confirmed live-order gate
```

The seven analysts run independently in parallel, as the notebook requires.
Everything after their results is a deterministic ordered pipeline. The Risk
Analyst is one of the seven evidence providers; the final Risk Gate is a
separate post-synthesis veto and cannot be bypassed.

## Gradle modules

- `core-model`: immutable market, GEX, agent, risk, broker, order and audit schemas.
- `core-market`: normalization, option validation, Greeks, GEX, Gamma Flip,
  shared snapshot creation and freshness.
- `core-agents`: seven analysts, validation, scorecard, conflicts, full debate,
  facilitator, fund manager and three plans.
- `core-risk`: staleness, sizing, exposure and live-eligibility vetoes.
- `core-broker`: common broker contract, registry, ephemeral sessions and
  independently testable provider adapters.
- `core-execution`: idempotency, audit, kill switch and confirmation-bound execution.
- `core-paper`: offline paper broker, fills and portfolio lifecycle.
- `core-backtest`: planned no-lookahead engine; not present in this build.
- `core-storage`: planned Android-only persistence for non-secret paper/audit/settings data; not present in this build.
- `app`: Compose presentation and lifecycle only; it must not calculate GEX,
  Greeks, order quantity or eligibility.

## Shared-snapshot invariant

One builder normalizes and validates quote/options input, calculates Greeks and
GEX once, assigns a snapshot ID/input version/timestamp/source, and passes that
same version to every analyst. Analysis, plan, risk decision and order must all
reference the same current version. Missing, invalid, mixed-symbol or stale data
blocks execution rather than producing invented values.

The Kotlin GEX port must regression-match the upstream formula and interpolated
positive-to-nonpositive Gamma Flip behavior in `analysis/gex.py`.

## Mobile-only broker boundary

The phone remains an outbound HTTPS/WebSocket client to the selected broker. A
Kuber server is not involved. Provider credentials are never embedded in the
APK, committed, logged, persisted, placed in SavedState or included in crash
reports. A personal-use Zerodha API key/secret may be entered for one session,
held only in volatile process memory for the token exchange, and cleared on
logout/process death.

This is an explicit deviation from the notebook's stronger rule that Android
never handles a raw provider secret. Zerodha requires the API secret during
token exchange; without an external backend it must exist temporarily on the
phone. This mode is not suitable for distributing a public multi-user app.

Angel One cannot be described as anywhere/mobile-live while it requires a
registered static public IPv4. A changing mobile address does not satisfy that
provider constraint. Fyers and Angel One remain unavailable until their direct
mobile flows pass current provider and contract tests.

## Release gates

The APK is not release-ready until all of these are evidenced:

1. No endpoint field, `api.example.com`, laptop Kuber URL or FastAPI call exists.
2. Exactly seven structured analyst results share one input version.
3. GEX/expiry/Gamma Flip fixtures match upstream; invalid and stale inputs fail closed.
4. Partial analyst failure produces `UNAVAILABLE` without cancelling the other six.
5. Conflicts, both arguments, both rebuttals, facilitator and fund manager are present.
6. The fund manager states whether GEX supports, opposes or is neutral.
7. Exactly three plans include entry, stop, target, size and reward/risk.
8. Final risk veto blocks stale, invalid, over-risk and version-mismatched orders.
9. Offline paper order, portfolio, audit and restart-safe idempotency tests pass.
10. Broker adapters pass independently; a provider is never marked connected from a mock.
11. Loading, error, freshness and reconnect states are visible in Android.
12. Secret scans cover source, APK resources, logs and persistent stores.
13. Unit, integration, Android build, safety and security review gates pass.
14. Live submission remains separately confirmed and cannot be triggered by an analyst.
