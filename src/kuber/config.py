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

    @classmethod
    def from_environment(cls) -> "KuberSettings":
        token = os.environ.get("KUBER_API_TOKEN") or None
        environment = os.environ.get("KUBER_ENVIRONMENT", "development")
        public_base_url = os.environ.get("KUBER_PUBLIC_BASE_URL") or None
        static_ipv4 = os.environ.get("KUBER_ANGEL_ONE_STATIC_IPV4") or None
        live_orders_enabled = os.environ.get("KUBER_ENABLE_LIVE_ORDERS", "false").lower() == "true"
        openai_api_key = os.environ.get("OPENAI_API_KEY") or None
        openai_model = os.environ.get("KUBER_OPENAI_MODEL", "gpt-5")
        if environment.lower() == "production" and not token:
            raise RuntimeError("KUBER_API_TOKEN is required in production")
        if live_orders_enabled and (not public_base_url or not public_base_url.startswith("https://") or not static_ipv4):
            raise RuntimeError("live Angel One orders require HTTPS public URL and registered static IPv4")
        return cls(token, environment, public_base_url, static_ipv4, live_orders_enabled, openai_api_key, openai_model)
