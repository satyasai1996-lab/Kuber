import hashlib
import json
import unittest

from kuber.brokers.kite_sandbox import KiteSandboxConnector


class KiteSandboxTests(unittest.TestCase):
    def test_exchanges_a_one_time_request_token_without_exposing_it(self) -> None:
        captured = {}

        def transport(request):
            captured["url"] = request.full_url
            captured["body"] = request.data.decode()
            return json.dumps({"data": {"access_token": "demo-session", "user_id": "SANDBOX1"}}).encode()

        connector = KiteSandboxConnector(transport=transport)
        result = connector.connect({"request_token": "one-time-token"})
        self.assertEqual(result.broker, "zerodha_sandbox")
        self.assertEqual(result.status, "connected")
        self.assertEqual(connector.access_token, "demo-session")
        self.assertIn("/oms/session/token", captured["url"])
        expected = hashlib.sha256(b"sandboxdemoone-time-tokensandboxdemo-secret").hexdigest()
        self.assertIn(expected, captured["body"])

    def test_login_url_is_the_official_demo_login(self) -> None:
        self.assertEqual(
            KiteSandboxConnector().login_url(),
            "https://sandbox.kite.trade/connect/login?api_key=sandboxdemo",
        )
