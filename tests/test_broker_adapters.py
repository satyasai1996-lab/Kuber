from datetime import datetime, timezone
import unittest

from kuber.brokers.providers import AngelOneBroker, FyersBroker, ZerodhaBroker
from kuber.models import OrderRequest, OrderResponse, OrderSide, Quote, TradingMode


class FakeGateway:
    def quote(self, symbol): return Quote(symbol, 100, datetime.now(timezone.utc), "fake")
    def option_chain(self, symbol): return ()
    def positions(self): return ()
    def holdings(self): return ()
    def funds(self): return 1_000.0
    def place(self, request): return OrderResponse("gateway-order", "ACCEPTED", "fake", request.mode, request.idempotency_key)


class BrokerAdapterTests(unittest.TestCase):
    def test_all_documented_adapters_conform_to_controlled_live_gate(self) -> None:
        request = OrderRequest("any", TradingMode.LIVE, "NIFTY", OrderSide.BUY, 1, "MARKET", "adapter-live-key")
        for adapter_type in (AngelOneBroker, ZerodhaBroker, FyersBroker):
            adapter = adapter_type(FakeGateway())
            self.assertFalse(adapter.supports_live_orders)
            with self.assertRaises(PermissionError):
                adapter.place_order(request)
