"""Zerodha Kite Connect sandbox authentication boundary.

The official sandbox uses shared demo credentials and never routes an order to a
real Zerodha account.  Its short-lived access token is held only in this
backend process; it is never returned to, or stored by, the Android client.
"""
from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
import json
from typing import Callable
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from kuber.brokers.connection import BrokerConnection


SandboxTransport = Callable[[Request], bytes]


def _urlopen(request: Request) -> bytes:
    with urlopen(request, timeout=15) as response:  # noqa: S310 - fixed official HTTPS host
        return response.read()


@dataclass
class KiteSandboxConnector:
    """Exchanges a sandbox request token for an in-memory demo session."""

    api_key: str = "sandboxdemo"
    api_secret: str = "sandboxdemo-secret"
    root: str = "https://sandbox.kite.trade"
    transport: SandboxTransport = _urlopen
    access_token: str | None = None
    user_id: str | None = None

    def login_url(self) -> str:
        return f"{self.root}/connect/login?{urlencode({'api_key': self.api_key})}"

    def connect(self, credentials: dict[str, str]) -> BrokerConnection:
        request_token = credentials.get("request_token", "").strip()
        if not request_token:
            raise ValueError("Zerodha sandbox requires the one-time request_token from its login page")
        checksum = sha256(f"{self.api_key}{request_token}{self.api_secret}".encode()).hexdigest()
        body = urlencode({"api_key": self.api_key, "request_token": request_token, "checksum": checksum}).encode()
        request = Request(
            f"{self.root}/oms/session/token",
            data=body,
            headers={"X-Kite-Version": "3", "Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        try:
            payload = json.loads(self.transport(request).decode())
            data = payload["data"]
            self.access_token = data["access_token"]
            self.user_id = data["user_id"]
        except (KeyError, TypeError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RuntimeError("Zerodha sandbox returned an invalid authentication response") from error
        return BrokerConnection("zerodha_sandbox", f"sandbox:{self.user_id}", "connected")
