# Laptop-first Angel One deployment

Kuber follows the project notebook: Android is the control layer; the laptop FastAPI service is the intelligence and execution layer. The phone never stores Angel One secrets or calls Angel One order endpoints directly.

## Test now: private mobile access

Run the backend on the laptop and connect the phone through a private encrypted network. Configure the Android debug build with the laptop's private-network address and a Kuber API token. Do not expose port 8000 publicly and do not submit broker credentials over HTTP.

## Required before enabling Angel One live orders

1. Obtain a fixed public IPv4 from the home ISP.
2. Register that exact IPv4 as the Angel One Primary Static IP.
3. Obtain a domain and serve `KUBER_PUBLIC_BASE_URL` over HTTPS with a valid certificate.
4. Register the exact callback and postback URLs from `.env.example` in the Angel One app form.
5. Restrict the laptop firewall/router to HTTPS and the minimum required inbound paths. Keep FastAPI behind a TLS reverse proxy; never put broker credentials in the APK, URL query strings, logs, or source code.
6. Keep `KUBER_ENABLE_LIVE_ORDERS=false` until paper orders, callback validation, postback idempotency, risk vetoes, audit records and broker-controlled tests pass.

## Home test limitation

A dynamic mobile/home IP and a private encrypted-network address are suitable for secure remote control of Kuber, but they do not replace Angel One's registered public static IPv4 requirement for live order traffic.
