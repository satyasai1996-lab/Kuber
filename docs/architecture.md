# Kuber Architecture

## Responsibilities

Kuber follows a strict client/server boundary. Android visualizes state and submits explicit user actions. It does not calculate GEX, call a broker directly, or hold broker secrets.

```text
provider payloads -> MarketDataNormalizer -> SharedMarketIntelligence
                                              | immutable GEXSnapshot
                                              v
             Technical / Fundamental / Options / News / Sentiment / Sector / Risk
                                              |
                                      scorecard + debate + fund-manager plan
                                              |
                                         RiskEngine veto
                                              |
                                     ExecutionService + audit event
```

## Safety invariants

- A `GexSnapshot` is built once from normalized option contracts and has an ID, source, and timestamp.
- Every `AgentResult` records the same market-intelligence version and input timestamp.
- A stale GEX snapshot, missing direction, or invalid stop blocks execution.
- The `RiskEngine` owns position sizing and can reduce size in a negative-gamma regime.
- `MockBroker` permits paper orders only; production broker adapters must implement `BaseBroker` and opt in to live execution.
- A live order cannot be submitted without a separate `confirmed=true` request, an idempotency key, an enabled broker, and an audit event.

## Extension points

- Replace deterministic analyst implementations with LLM-backed classes implementing `Analyst`.
- Add Angel One, Zerodha, and Fyers adapters that implement `BaseBroker`; preserve Kuber's normalized models at the boundary.
- Attach a durable `AuditLog` and authenticated user/session middleware before enabling remote API access.
