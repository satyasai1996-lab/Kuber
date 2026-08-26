# Broker Validation Gate

Kuber keeps every provider adapter disabled for live execution until it passes this gate. Paper orders and historical AI-bot backtests do not require broker credentials.

## Required for each broker

1. Backend-only secret injection: API key, OAuth/TOTP/session material, and any provider-specific identifier must be supplied by deployment secrets. They must never be placed in the Android application.
2. A provider `BrokerGateway` implementation must normalize quote, option-chain, portfolio, funds, and order responses into Kuber models.
3. Controlled sandbox or minimum-size paper/approved test must validate authentication, market data, idempotency, status reconciliation, cancellation, reconnect, and audit logging.
4. A production approver enables `live_enabled` only after the preceding test evidence is recorded.

## Provider-specific operational constraint

Angel One's official SmartAPI documentation states that order requests use its secure order APIs and require the source IP to match a registered static IP. Kuber therefore never enables the Angel One live adapter from an Android client or an unapproved dynamic host.

## Live-order invariants

- Risk Manager approval and a current validated GEX snapshot are required.
- The user must submit a distinct explicit confirmation request.
- Every request carries an idempotency key and produces an audit event.
- Broker-side risk controls remain authoritative.
