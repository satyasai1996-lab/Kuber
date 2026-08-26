from datetime import datetime, timezone
import unittest

from kuber.brokers.zerodha import ZerodhaKiteGateway, ZerodhaOAuthConnector
from kuber.models import OrderRequest, OrderSide, TradingMode


class FakeKiteClient:
    def __init__(self, api_key: str) -> None:
        self.api_key = api_key
        self.access_token: str | None = None
        self.session_request_token: str | None = None

    def login_url(self) -> str:
        return f"https://kite.zerodha.com/connect/login?v=3&api_key={self.api_key}"

    def generate_session(self, request_token: str, api_secret: str):
        self.session_request_token = request_token
        if api_secret != "server-secret":
            raise ValueError("unexpected secret")
        return {"access_token": "backend-only-token", "user_id": "AB1234"}

    def set_access_token(self, access_token: str) -> None:
        self.access_token = access_token

    def quote(self, instruments):
        return {instruments[0]: {"last_price": 22_100.5, "timestamp": datetime(2026, 8, 26, tzinfo=timezone.utc), "volume": 450}}

    def positions(self): return {"net": [{"tradingsymbol": "INFY", "quantity": 1}]}
    def holdings(self): return [{"tradingsymbol": "INFY", "quantity": 1}]
    def margins(self): return {"equity": {"available": {"cash": 12_345}}}
    def place_order(self, **kwargs): return "order-123"


class ZerodhaOAuthTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = FakeKiteClient("kite-key")
        self.connector = ZerodhaOAuthConnector(
            "kite-key", "server-secret", client_factory=lambda _: self.client,
        )

    def test_exchanges_only_the_callback_token_and_registers_a_backend_broker(self) -> None:
        self.assertIn("api_key=kite-key", self.connector.login_url())
        connection = self.connector.connect({"request_token": "one-time-token"})
        self.assertEqual(connection.connection_reference, "kite:AB1234")
        self.assertEqual(self.client.session_request_token, "one-time-token")
        self.assertEqual(self.client.access_token, "backend-only-token")

        broker = self.connector.connected_broker()
        quote = broker.get_quote("NIFTY")
        self.assertEqual(quote.symbol, "NIFTY")
        self.assertEqual(quote.source, "zerodha")
        self.assertFalse(broker.supports_live_orders)

    def test_gateway_rejects_direct_index_order(self) -> None:
        gateway = ZerodhaKiteGateway(self.client)
        request = OrderRequest("zerodha", TradingMode.PAPER, "NIFTY", OrderSide.BUY, 1, "MARKET", "safe-key-123")
        with self.assertRaisesRegex(ValueError, "index cannot be ordered"):
            gateway.place(request)
