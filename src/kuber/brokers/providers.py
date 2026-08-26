"""Controlled adapters for Angel One, Zerodha, and Fyers.

Secrets and provider SDKs are injected by backend deployment code, never by the
Android client. These adapters deliberately default to paper-only operation.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from kuber.brokers.base import BaseBroker
from kuber.models import OptionContract, OrderRequest, OrderResponse, Quote, TradingMode


class BrokerGateway(Protocol):
    """Minimal provider SDK boundary; implementations own OAuth/TOTP and HTTP."""

    def quote(self, symbol: str) -> Quote: ...
    def option_chain(self, symbol: str) -> tuple[OptionContract, ...]: ...
    def positions(self) -> tuple[dict[str, object], ...]: ...
    def holdings(self) -> tuple[dict[str, object], ...]: ...
    def funds(self) -> float: ...
    def place(self, request: OrderRequest) -> OrderResponse: ...


@dataclass
class ControlledBroker(BaseBroker):
    """Shared guardrails for every credentialed provider adapter."""

    name: str
    gateway: BrokerGateway
    live_enabled: bool = False

    def get_quote(self, symbol: str) -> Quote:
        return self.gateway.quote(symbol)

    def get_options_chain(self, symbol: str) -> tuple[OptionContract, ...]:
        return self.gateway.option_chain(symbol)

    def get_positions(self) -> tuple[dict[str, object], ...]:
        return self.gateway.positions()

    def get_holdings(self) -> tuple[dict[str, object], ...]:
        return self.gateway.holdings()

    def get_funds(self) -> float:
        return self.gateway.funds()

    @property
    def supports_live_orders(self) -> bool:
        return self.live_enabled

    def place_order(self, request: OrderRequest) -> OrderResponse:
        if request.mode == TradingMode.LIVE and not self.live_enabled:
            raise PermissionError(f"{self.name} live execution is disabled pending controlled validation")
        return self.gateway.place(request)


class AngelOneBroker(ControlledBroker):
    def __init__(self, gateway: BrokerGateway, live_enabled: bool = False) -> None:
        super().__init__("angel_one", gateway, live_enabled)


class ZerodhaBroker(ControlledBroker):
    def __init__(self, gateway: BrokerGateway, live_enabled: bool = False) -> None:
        super().__init__("zerodha", gateway, live_enabled)


class FyersBroker(ControlledBroker):
    def __init__(self, gateway: BrokerGateway, live_enabled: bool = False) -> None:
        super().__init__("fyers", gateway, live_enabled)
