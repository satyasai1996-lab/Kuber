import unittest

from fastapi.testclient import TestClient

from kuber.api.app import create_app
from kuber.config import KuberSettings


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
        latest = self.client.get("/analysis/latest/NIFTY")
        self.assertEqual(latest.status_code, 200)
        self.assertEqual(latest.json()["analysis_id"], body["analysis_id"])

    def test_paper_order_and_live_gate(self) -> None:
        self.client.post("/analysis/analyze", json=PAYLOAD)
        order = {"symbol": "NIFTY", "side": "BUY", "quantity": 1, "idempotency_key": "api-paper-123"}
        self.assertEqual(self.client.post("/orders/paper", json=order).status_code, 200)
        live = {**order, "idempotency_key": "api-live-123", "confirmed": False}
        self.assertEqual(self.client.post("/orders/live/confirm", json=live).status_code, 409)

    def test_demo_starts_a_visible_paper_only_market_intelligence_session(self) -> None:
        response = self.client.post("/demo/start")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["mode"], "PAPER_DEMO")
        self.assertEqual(body["source"], "demo_fixture")
        self.assertEqual(len(body["analysis"]["agents"]), 7)
        self.assertEqual(body["gex"]["snapshot_id"], body["analysis"]["intelligence"]["snapshot"]["snapshot_id"])
        paper = self.client.post("/orders/paper", json={"symbol": "NIFTY", "side": "BUY", "quantity": 1, "idempotency_key": "demo-paper-123"})
        self.assertEqual(paper.status_code, 200)

    def test_creates_alert_with_supported_kind(self) -> None:
        response = self.client.post("/alerts", json={"symbol": "NIFTY", "kind": "GEX_REGIME", "condition": "NEGATIVE"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["kind"], "GEX_REGIME")

    def test_backtest_accepts_risk_approved_ai_signal(self) -> None:
        candles = [
            {"timestamp": "2026-01-01T00:00:00Z", "open": 100, "high": 102, "low": 99, "close": 101},
            {"timestamp": "2026-01-02T00:00:00Z", "open": 105, "high": 108, "low": 104, "close": 107},
            {"timestamp": "2026-01-03T00:00:00Z", "open": 110, "high": 112, "low": 109, "close": 111},
        ]
        signals = [{"timestamp": "2026-01-01T00:00:00Z", "bias": "BULLISH", "confidence": 80, "risk_approved": True, "rationale": ["validated bot consensus"]}]
        response = self.client.post("/backtest", json={"candles": candles, "signals": signals, "initial_capital": 1000, "allocation_percent": 100, "transaction_cost_bps": 0})
        self.assertEqual(response.status_code, 200)
        self.assertGreater(response.json()["total_return_percent"], 0)

    def test_configured_token_protects_every_data_endpoint(self) -> None:
        client = TestClient(create_app(settings=KuberSettings(api_token="test-token")))
        self.assertEqual(client.get("/brokers").status_code, 401)
        self.assertEqual(client.get("/brokers", headers={"Authorization": "Bearer test-token"}).status_code, 200)

    def test_broker_connect_never_exposes_unconfigured_gateway(self) -> None:
        response = self.client.post("/brokers/connect", json={"broker": "angel_one", "credentials": {"api_key": "temporary", "client_id": "user"}})
        self.assertEqual(response.status_code, 503)

    def test_real_zerodha_login_requires_backend_configuration(self) -> None:
        response = self.client.get("/brokers/zerodha/login-url")
        self.assertEqual(response.status_code, 503)
        self.assertIn("KUBER_ZERODHA_API_KEY", response.json()["detail"])

    def test_openai_opinion_requires_backend_key_and_existing_analysis(self) -> None:
        self.client.post("/analysis/analyze", json=PAYLOAD)
        response = self.client.post("/analysis/openai-opinion/NIFTY")
        self.assertEqual(response.status_code, 503)
        self.assertIn("OPENAI_API_KEY", response.json()["detail"])
