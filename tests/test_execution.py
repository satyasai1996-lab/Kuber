import unittest

from kuber.brokers.mock import MockBroker
from kuber.execution.service import BrokerRegistry, ExecutionService
from kuber.models import OrderRequest, OrderSide, TradingMode


class ExecutionTests(unittest.TestCase):
    def setUp(self) -> None:
        registry = BrokerRegistry()
        registry.register(MockBroker())
        self.execution = ExecutionService(registry)

    def test_paper_order_is_idempotent_and_audited(self) -> None:
        request = OrderRequest("mock", TradingMode.PAPER, "NIFTY", OrderSide.BUY, 1, "MARKET", "paper-key-123")
        first = self.execution.submit_paper(request)
        second = self.execution.submit_paper(request)
        self.assertEqual(first.order_id, second.order_id)
        self.assertEqual(len(self.execution.audit_log.events), 2)

    def test_live_order_requires_explicit_confirmation_and_enabled_broker(self) -> None:
        request = OrderRequest("mock", TradingMode.LIVE, "NIFTY", OrderSide.BUY, 1, "MARKET", "live-key-123")
        with self.assertRaises(PermissionError):
            self.execution.confirm_live(request, confirmed=False)
        with self.assertRaises(PermissionError):
            self.execution.confirm_live(request, confirmed=True)
