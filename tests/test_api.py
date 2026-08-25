import unittest

from fastapi.testclient import TestClient

from kuber.api.app import create_app


PAYLOAD = {
    "symbol": "NIFTY",
    "quote": {"last_price": 22000, "vwap": 21900, "source": "mock"},
    "options": [
        {"strike": 21900, "expiry": "2026-08-27", "option_type": "CE", "open_interest": 150, "implied_volatility": 14, "gamma": 0.02, "last_price": 100, "lot_size": 25},
        {"strike": 22100, "expiry": "2026-08-27", "option_type": "PE", "open_interest": 70, "implied_volatility": 15, "gamma": 0.02, "last_price": 100, "lot_size": 25},
    ],
    "fundamentals": {"quality_score": 20}, "news_bias": 12, "sentiment_score": 15, "sector_score": 10,
}


class ApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(create_app())

    def test_analysis_exposes_shared_gex_and_all_agents(self) -> None:
        response = self.client.post("/analysis/analyze", json=PAYLOAD)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(len(body["agents"]), 7)
        snapshot = self.client.get("/analysis/gex/NIFTY")
        self.assertEqual(snapshot.status_code, 200)
        self.assertEqual(snapshot.json()["snapshot_id"], body["intelligence"]["snapshot"]["snapshot_id"])

    def test_paper_order_and_live_gate(self) -> None:
        self.client.post("/analysis/analyze", json=PAYLOAD)
        order = {"symbol": "NIFTY", "side": "BUY", "quantity": 1, "idempotency_key": "api-paper-123"}
        self.assertEqual(self.client.post("/orders/paper", json=order).status_code, 200)
        live = {**order, "idempotency_key": "api-live-123", "confirmed": False}
        self.assertEqual(self.client.post("/orders/live/confirm", json=live).status_code, 409)
