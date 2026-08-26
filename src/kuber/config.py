"""Runtime configuration. Secrets are deployment inputs, never source-controlled."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class KuberSettings:
    api_token: str | None = None
    environment: str = "development"
    public_base_url: str | None = None
    angel_one_static_ipv4: str | None = None
    live_orders_enabled: bool = False
    openai_api_key: str | None = None
    openai_model: str = "gpt-5"
    zerodha_api_key: str | None = None
    zerodha_api_secret: str | None = None
    zerodha_redirect_url: str | None = None

    @classmethod
    def from_environment(cls) -> "KuberSettings":
        token = os.environ.get("KUBER_API_TOKEN") or None
        environment = os.environ.get("KUBER_ENVIRONMENT", "development")
        public_base_url = os.environ.get("KUBER_PUBLIC_BASE_URL") or None
        static_ipv4 = os.environ.get("KUBER_ANGEL_ONE_STATIC_IPV4") or None
        live_orders_enabled = os.environ.get("KUBER_ENABLE_LIVE_ORDERS", "false").lower() == "true"
        openai_api_key = os.environ.get("OPENAI_API_KEY") or None
        openai_model = os.environ.get("KUBER_OPENAI_MODEL", "gpt-5")
        zerodha_api_key = os.environ.get("KUBER_ZERODHA_API_KEY") or None
        zerodha_api_secret = os.environ.get("KUBER_ZERODHA_API_SECRET") or None
        zerodha_redirect_url = os.environ.get("KUBER_ZERODHA_REDIRECT_URL") or None
        if environment.lower() == "production" and not token:
            raise RuntimeError("KUBER_API_TOKEN is required in production")
        if live_orders_enabled and (environment.lower() != "production" or not public_base_url or not public_base_url.startswith("https://")):
            raise RuntimeError("live orders require production mode and an HTTPS public URL")
        if os.environ.get("KUBER_ENABLE_ANGEL_ONE_LIVE", "false").lower() == "true" and not static_ipv4:
            raise RuntimeError("live Angel One orders require a registered static IPv4")
        if bool(zerodha_api_key) != bool(zerodha_api_secret):
            raise RuntimeError("configure both KUBER_ZERODHA_API_KEY and KUBER_ZERODHA_API_SECRET")
        return cls(
            api_token=token,
            environment=environment,
            public_base_url=public_base_url,
            angel_one_static_ipv4=static_ipv4,
            live_orders_enabled=live_orders_enabled,
            openai_api_key=openai_api_key,
            openai_model=openai_model,
            zerodha_api_key=zerodha_api_key,
            zerodha_api_secret=zerodha_api_secret,
            zerodha_redirect_url=zerodha_redirect_url,
        )
