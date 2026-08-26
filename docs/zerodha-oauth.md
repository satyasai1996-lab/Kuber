# Zerodha connection in Kuber

Kuber uses the real Kite Connect OAuth pattern from the reference project,
adapted for an Android client and backend-secret boundary.

1. Create a Kite Connect app in the user’s Zerodha developer account and
   register the Kuber backend callback URL, for example
   `https://your-domain.example/brokers/zerodha/callback`.
2. On the backend only, install broker support with `pip install -e '.[brokers]'`
   and set `KUBER_ZERODHA_API_KEY` plus `KUBER_ZERODHA_API_SECRET`. Do not add
   either value to the APK, Android resources or a Git commit.
3. In Kuber Android, set the HTTPS Kuber API endpoint and select **Connect
   Zerodha**. Kite renders the password and 2FA page; Kuber receives only the
   one-time callback token.
4. The backend exchanges that token with the API secret, retains the session
   server-side and registers the normalised Zerodha broker adapter.

The previous `sandboxdemo` route is retained solely as an isolated developer
test. It uses a separate sandbox account and cannot authenticate a real Zerodha
account, which is why it can produce a “wrong login” outcome for regular Kite
credentials.

## Live-order boundary

A successful OAuth login only connects data/portfolio access. Kuber keeps live
orders disabled unless the production backend has HTTPS, a configured API token,
the explicit live flag, a risk-approved plan and a separate per-order user
confirmation. Angel One has its additional static IPv4 registration rule only
when Angel One live execution is enabled.
