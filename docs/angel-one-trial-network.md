# Angel One trial network configuration

This temporary setup uses the public address observed from the laptop on
2026-08-26. It is a trial value only: if the ISP changes the address, replace
every occurrence before using the app again.

## Values for the Angel One Add App form

| Field | Trial value |
| --- | --- |
| App Name | `Kuber` |
| Redirect URL | `https://9-129-26-2.sslip.io/auth/angelone/callback` |
| Post back URL | `https://9-129-26-2.sslip.io/webhooks/angelone/orders` |
| Primary Static IP | `9.129.26.2` **only if your ISP confirms it is static** |
| Secondary Static IP | Leave blank |

Do not enter the laptop LAN address (`192.168.31.75`) into Angel One. It is
only the router's internal forwarding target.

## Required router configuration

Create exactly these two TCP port-forwarding rules to the laptop:

| Public port | Laptop destination | Purpose |
| --- | --- | --- |
| 80 | `192.168.31.75:80` | HTTPS certificate validation / HTTP redirect |
| 443 | `192.168.31.75:443` | Kuber HTTPS API and broker callbacks |

Never forward public port 8000. It is only the private FastAPI listener behind
the Caddy HTTPS proxy.

## Required laptop configuration

1. Change the `Satya` Wi-Fi profile to **Private**.
2. In an Administrator PowerShell, permit only Caddy's inbound TCP ports 80 and
   443 on the Private profile. Do not create an unrestricted inbound rule.
3. Install Caddy from its official site, set these process environment values,
   and run it with `infra/Caddyfile`:

```powershell
$env:KUBER_PUBLIC_HOST = "9-129-26-2.sslip.io"
$env:KUBER_ACME_EMAIL = "your-email@example.com"
caddy run --config infra/Caddyfile
```

4. Run Kuber with `KUBER_ENVIRONMENT=production`, a long random
   `KUBER_API_TOKEN`, `KUBER_PUBLIC_BASE_URL=https://9-129-26-2.sslip.io`, and
   `KUBER_ANGEL_ONE_STATIC_IPV4=9.129.26.2`.

Only enable `KUBER_ENABLE_LIVE_ORDERS=true` after the public HTTPS health
endpoint and Angel One callback are verified, the broker session is connected,
and the risk/explicit-confirmation gates are retained.
