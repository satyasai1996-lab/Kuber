"""Runtime configuration. Secrets are deployment inputs, never source-controlled."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class KuberSettings:
    api_token: str | None = None
    environment: str = "development"

    @classmethod
    def from_environment(cls) -> "KuberSettings":
        token = os.environ.get("KUBER_API_TOKEN") or None
        environment = os.environ.get("KUBER_ENVIRONMENT", "development")
        if environment.lower() == "production" and not token:
            raise RuntimeError("KUBER_API_TOKEN is required in production")
        return cls(token, environment)
