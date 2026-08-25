"""Safe in-memory broker used for development and paper-trading tests."""
from __future__ import annotations

from dataclasses import dataclass, field
from uuid import uuid4

from kuber.brokers.base import BaseBroker
from kuber.models import OptionContract, OrderRequest, OrderResponse, Quote, TradingMode


@dataclass
class MockBroker(BaseBroker):
    name: str = "mock"
    quote: Quote | None = None
    option_chain: tuple[OptionContract, ...] = ()
    funds: float = 200_000.0
    _orders: dict[str, OrderResponse] = field(default_factory=dict)

    def get_quote(self, symbol: str) -> Quote:
        if self.quote is None or self.quote.symbol != symbol.upper():
            raise LookupError(f"no mock quote for {symbol}")
        return self.quote

    def get_options_chain(self, symbol: str) -> tuple[OptionContract, ...]:
        if not self.option_chain or self.option_chain[0].underlying != symbol.upper():
            raise LookupError(f"no mock option chain for {symbol}")
        return self.option_chain

    def get_positions(self) -> tuple[dict[str, object], ...]:
        return ()

    def get_holdings(self) -> tuple[dict[str, object], ...]:
        return ()

    def get_funds(self) -> float:
        return self.funds

    def place_order(self, request: OrderRequest) -> OrderResponse:
        if request.mode != TradingMode.PAPER:
            raise PermissionError("MockBroker only permits paper orders")
        if request.quantity <= 0:
            raise ValueError("order quantity must be positive")
        existing = self._orders.get(request.idempotency_key)
        if existing:
            return existing
        response = OrderResponse(uuid4().hex, "FILLED", self.name, request.mode, request.idempotency_key)
        self._orders[request.idempotency_key] = response
        return response
