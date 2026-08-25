"""Broker-independent execution and portfolio interface."""
from __future__ import annotations

from abc import ABC, abstractmethod

from kuber.models import OptionContract, OrderRequest, OrderResponse, Quote


class BaseBroker(ABC):
    name: str

    @abstractmethod
    def get_quote(self, symbol: str) -> Quote: ...

    @abstractmethod
    def get_options_chain(self, symbol: str) -> tuple[OptionContract, ...]: ...

    @abstractmethod
    def get_positions(self) -> tuple[dict[str, object], ...]: ...

    @abstractmethod
    def get_holdings(self) -> tuple[dict[str, object], ...]: ...

    @abstractmethod
    def get_funds(self) -> float: ...

    @abstractmethod
    def place_order(self, request: OrderRequest) -> OrderResponse: ...

    @property
    def supports_live_orders(self) -> bool:
        return False
