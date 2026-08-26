from datetime import datetime, timedelta, timezone
import unittest

from kuber.backtest.engine import BacktestConfig, BotSignalBacktester
from kuber.models import Bias, BotSignal, Candle


class BacktestTests(unittest.TestCase):
    def test_executes_validated_bot_signal_on_next_open_without_lookahead(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        candles = (
            Candle(start, 100, 102, 99, 101),
            Candle(start + timedelta(days=1), 105, 108, 104, 107),
            Candle(start + timedelta(days=2), 110, 112, 109, 111),
        )
        signals = (BotSignal(start, Bias.BULLISH, 80, True, ("seven-agent consensus",)),)
        result = BotSignalBacktester(BacktestConfig(initial_capital=1_000, allocation_percent=100, transaction_cost_bps=0)).run(candles, signals)

        self.assertEqual(len(result.trades), 1)
        self.assertEqual(result.trades[0].entry_price, 105)  # next session open, not signal candle close
        self.assertEqual(result.trades[0].exit_price, 111)
        self.assertGreater(result.total_return_percent, 0)

    def test_rejects_vetoed_bot_signal(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        candles = (Candle(start, 100, 101, 99, 100), Candle(start + timedelta(days=1), 101, 102, 100, 101))
        signal = BotSignal(start, Bias.BULLISH, 99, False, ("risk veto",))
        result = BotSignalBacktester().run(candles, (signal,))
        self.assertEqual(result.rejected_signals, 1)
        self.assertEqual(result.trades, ())
