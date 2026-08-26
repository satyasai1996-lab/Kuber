"""One-time broker credential hand-off with no Android-side credential storage."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class BrokerConnection:
    broker: str
    connection_reference: str
    status: str


class BrokerConnector(Protocol):
    """Deployment adapter that authenticates a provider and stores secrets in a server vault."""

    def connect(self, credentials: dict[str, str]) -> BrokerConnection: ...


class BrokerConnectionService:
    SUPPORTED = {"angel_one", "zerodha", "zerodha_sandbox", "fyers"}

    def __init__(self, connectors: dict[str, BrokerConnector] | None = None) -> None:
        self._connectors = connectors or {}

    def connect(self, broker: str, credentials: dict[str, str]) -> BrokerConnection:
        normalized = broker.lower().replace(" ", "_")
        if normalized not in self.SUPPORTED:
            raise ValueError("unsupported broker")
        if not credentials or any(not value.strip() for value in credentials.values()):
            raise ValueError("all submitted broker fields must be non-empty")
        connector = self._connectors.get(normalized)
        if connector is None:
            raise RuntimeError(f"{normalized} gateway is not configured on this Kuber backend")
        try:
            return connector.connect(credentials)
        finally:
            # The API does not retain this request payload; deployment connectors must
            # persist encrypted server-side references only, never plain credentials.
            credentials.clear()

    def login_url(self, broker: str) -> str:
        """Return a provider login URL only when a connector explicitly exposes one."""
        normalized = broker.lower().replace(" ", "_")
        connector = self._connectors.get(normalized)
        login_url = getattr(connector, "login_url", None)
        if not callable(login_url):
            raise RuntimeError(f"{normalized} does not expose a configured login flow")
        return str(login_url())
