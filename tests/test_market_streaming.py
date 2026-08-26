from datetime import datetime, timezone
import unittest

from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from kuber.api.app import KuberServices, create_app
from kuber.config import KuberSettings
from kuber.market.streaming import MarketStreamBus
from kuber.models import Quote


class MarketStreamingTests(unittest.TestCase):
    def test_bus_replays_and_fans_out_a_normalized_quote(self) -> None:
        bus = MarketStreamBus()
        subscriber = bus.subscribe("NIFTY")
        quote = Quote("NIFTY", 22_000, datetime.now(timezone.utc), "zerodha")
        bus.publish(quote)
        self.assertEqual(subscriber.get_nowait().last_price, 22_000)
        self.assertEqual(bus.latest("nifty"), quote)

    def test_websocket_replays_latest_quote_after_reconnect(self) -> None:
        services = KuberServices()
        services.stream.publish(Quote("NIFTY", 22_050, datetime.now(timezone.utc), "demo_fixture"))
        client = TestClient(create_app(services=services))
        with client.websocket_connect("/market/stream/NIFTY") as socket:
            self.assertEqual(socket.receive_json()["state"], "connected")
            payload = socket.receive_json()
            self.assertEqual(payload["kind"], "quote")
            self.assertEqual(payload["data"]["last_price"], 22_050)

    def test_websocket_requires_the_configured_backend_token(self) -> None:
        client = TestClient(create_app(settings=KuberSettings(api_token="test-token")))
        with self.assertRaises(WebSocketDisconnect):
            with client.websocket_connect("/market/stream/NIFTY"):
                pass

    def test_websocket_accepts_authorization_header_but_rejects_query_token(self) -> None:
        client = TestClient(create_app(settings=KuberSettings(api_token="test-token")))
        with client.websocket_connect(
            "/market/stream/NIFTY",
            headers={"Authorization": "Bearer test-token"},
        ) as socket:
            self.assertEqual(socket.receive_json()["state"], "connected")

        with self.assertRaises(WebSocketDisconnect):
            with client.websocket_connect("/market/stream/NIFTY?token=test-token"):
                pass
