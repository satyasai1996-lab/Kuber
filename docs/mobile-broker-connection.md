# Mobile broker connection

Kuber accepts Angel One, Zerodha, or Fyers connection details in a transient Android form. The Android UI never saves broker API keys, client IDs, request tokens, PINs, passwords, OTPs, or secrets in preferences, files, logs, backups, or the APK.

The app clears the form immediately after a one-time HTTPS hand-off to `POST /brokers/connect`. The backend only accepts the hand-off when a server-side broker gateway has been configured. A stock deployment returns `503` instead of retaining or exposing credentials.

Only a Kuber session token may be stored locally, using encrypted Android preferences. Production API deployments require `KUBER_API_TOKEN`; the application sends it as a bearer token.

Broker connections do not enable trading. Paper mode is the default. A live order still requires an enabled broker, an explicit confirmation flag, idempotency, an audit record, and risk approval.
