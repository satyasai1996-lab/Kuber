# Zerodha demo connection

Kuber's `zerodha_sandbox` connector uses Zerodha's official Kite Connect demo
sandbox. It is distinct from a production account and does not use real money.

1. Start the Kuber backend and open `GET /brokers/zerodha/sandbox/login-url`.
2. Open the returned `login_url` and complete the Zerodha sandbox login.
3. Copy the short-lived `request_token` from the callback URL.
4. Call `POST /brokers/connect` with this payload:

```json
{"broker":"zerodha_sandbox","credentials":{"request_token":"the-one-time-token"}}
```

The backend exchanges that token over HTTPS, retains only the in-memory demo
session, and returns a non-secret connection reference. The Android APK must
never contain a Kite secret or access token. Sandbox orders are limited by
Zerodha's sandbox rules, including LIMIT-only API orders.
