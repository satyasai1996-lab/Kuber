"""No-lookahead paper backtesting for validated Kuber AI-bot signals."""
from __future__ import annotations

from dataclasses import dataclass

from kuber.models import BacktestResult, BacktestTrade, Bias, BotSignal, Candle, OrderSide


@dataclass(frozen=True)
class BacktestConfig:
    initial_capital: float = 200_000.0
    allocation_percent: float = 25.0
    transaction_cost_bps: float = 10.0
    min_confidence: int = 55


class BotSignalBacktester:
    """Executes a decision at the next candle open to prevent lookahead bias."""

    def __init__(self, config: BacktestConfig | None = None) -> None:
        self.config = config or BacktestConfig()

    def run(self, candles: tuple[Candle, ...], signals: tuple[BotSignal, ...]) -> BacktestResult:
        if len(candles) < 2:
            raise ValueError("backtest requires at least two candles")
        ordered = tuple(sorted(candles, key=lambda candle: candle.timestamp))
        if tuple(candle.timestamp for candle in ordered) != tuple(dict.fromkeys(candle.timestamp for candle in ordered)):
            raise ValueError("candle timestamps must be unique")
        signal_by_time = {signal.timestamp: signal for signal in signals}
        cash = self.config.initial_capital
        quantity = 0
        entry_price = 0.0
        entry_time = None
        entry_signal: BotSignal | None = None
        trades: list[BacktestTrade] = []
        rejected = 0
        peak_equity = cash
        max_drawdown = 0.0
        pending: BotSignal | None = None

        def cost(notional: float) -> float:
            return notional * self.config.transaction_cost_bps / 10_000

        def close_position(candle: Candle) -> None:
            nonlocal cash, quantity, entry_price, entry_time, entry_signal
            if quantity == 0 or entry_time is None or entry_signal is None:
                return
            gross = (candle.open - entry_price) * quantity
            fees = cost(entry_price * quantity) + cost(candle.open * quantity)
            pnl = round(gross - fees, 2)
            cash += candle.open * quantity - cost(candle.open * quantity)
            trades.append(BacktestTrade(entry_time, candle.timestamp, OrderSide.BUY, quantity, entry_price, candle.open, pnl, entry_signal.rationale))
            quantity, entry_price, entry_time, entry_signal = 0, 0.0, None, None

        for candle in ordered:
            if pending is not None:
                if pending.bias == Bias.BULLISH and quantity == 0:
                    allocation = cash * self.config.allocation_percent / 100
                    units = int(allocation // candle.open)
                    if units > 0:
                        cash -= candle.open * units + cost(candle.open * units)
                        quantity, entry_price, entry_time, entry_signal = units, candle.open, candle.timestamp, pending
                elif pending.bias == Bias.BEARISH and quantity > 0:
                    close_position(candle)
                pending = None

            equity = cash + quantity * candle.close
            peak_equity = max(peak_equity, equity)
            max_drawdown = max(max_drawdown, (peak_equity - equity) / peak_equity * 100)
            candidate = signal_by_time.get(candle.timestamp)
            if candidate:
                if not candidate.risk_approved or candidate.confidence < self.config.min_confidence:
                    rejected += 1
                else:
                    pending = candidate

        if quantity > 0:
            final = ordered[-1]
            # Exit at final close because no next execution session exists.
            gross = (final.close - entry_price) * quantity
            fees = cost(entry_price * quantity) + cost(final.close * quantity)
            pnl = round(gross - fees, 2)
            cash += final.close * quantity - cost(final.close * quantity)
            trades.append(BacktestTrade(entry_time, final.timestamp, OrderSide.BUY, quantity, entry_price, final.close, pnl, entry_signal.rationale if entry_signal else ()))

        wins = sum(1 for trade in trades if trade.net_pnl > 0)
        return BacktestResult(
            initial_capital=self.config.initial_capital,
            final_equity=round(cash, 2),
            total_return_percent=round((cash - self.config.initial_capital) / self.config.initial_capital * 100, 3),
            max_drawdown_percent=round(max_drawdown, 3),
            win_rate_percent=round(wins / len(trades) * 100, 2) if trades else 0.0,
            trades=tuple(trades),
            rejected_signals=rejected,
        )
