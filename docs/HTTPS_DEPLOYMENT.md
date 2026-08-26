# Kuber HTTPS deployment

Kuber exposes FastAPI through an outbound Cloudflare Tunnel. Uvicorn listens on
`127.0.0.1` only, so the router and Windows firewall do not need an inbound port.
The Android app verifies a normal public TLS certificate with the platform trust
store and refuses cleartext HTTP.

## Trial tunnel

Install the current official Windows `cloudflared` binary, then run:

```powershell
.\deploy\start-trial-https.ps1
```

The launcher creates a random `https://*.trycloudflare.com` address and a random
64-character Kuber API token. While the launcher is running, the URL is written
to `.runtime/public-url.txt` and the token to `.runtime/api-token.txt`. Both are
excluded from Git; the token is removed on an orderly launcher shutdown. Enter them in
Android under **Safety → Verify HTTPS connection**. Android keeps the token in
memory only and clears it on disconnect or process shutdown.

An orderly launcher shutdown removes the token file. If its terminal or host is
terminated abruptly, the old token becomes unusable with that stopped backend;
the launcher deletes any stale runtime token before generating a new one on the
next start.

The trial launcher always sets `KUBER_ENABLE_LIVE_ORDERS=false`. Cloudflare says
Quick Tunnels are for testing and development, have no uptime SLA, use a random
hostname on every run and limit concurrent in-flight requests. They are not a
production broker callback or live-order deployment.

Official references:

- [Cloudflare Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
- [Official cloudflared downloads](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/)

## Persistent hostname upgrade

For a stable broker callback, add a domain to Cloudflare and create a remotely
managed or named tunnel. Keep its token/JSON credentials outside this repository.
Use `deploy/cloudflared-config.example.yml` as the origin-routing template and
set these backend values:

```text
KUBER_ENVIRONMENT=production
KUBER_PUBLIC_BASE_URL=https://api.your-domain.example
KUBER_ALLOWED_HOSTS=api.your-domain.example
KUBER_API_TOKEN=<at-least-32-random-characters>
KUBER_ENABLE_LIVE_ORDERS=false
```

Register the exact stable HTTPS callback with Zerodha or Angel One. HTTPS does
not replace Angel One's separately required registered static outbound IPv4.
Live orders remain gated by provider acceptance, paper testing, risk approval,
security review and explicit user confirmation.
