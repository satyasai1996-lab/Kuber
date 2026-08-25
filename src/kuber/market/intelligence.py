"""Immutable shared market-intelligence snapshots."""
from __future__ import annotations

from threading import RLock

from kuber.market.gex import GexCalculator
from kuber.models import MarketIntelligence, OptionContract, Quote


class SharedMarketIntelligence:
    def __init__(self, calculator: GexCalculator | None = None) -> None:
        self._calculator = calculator or GexCalculator()
        self._snapshots: dict[str, MarketIntelligence] = {}
        self._lock = RLock()

    def publish(self, quote: Quote, options: tuple[OptionContract, ...]) -> MarketIntelligence:
        snapshot = self._calculator.build_snapshot(
            symbol=quote.symbol,
            spot=quote.last_price,
            contracts=options,
            source=quote.source,
            timestamp=quote.timestamp,
        )
        intelligence = MarketIntelligence(snapshot=snapshot, quote=quote, option_chain=options)
        with self._lock:
            self._snapshots[quote.symbol] = intelligence
        return intelligence

    def get(self, symbol: str) -> MarketIntelligence | None:
        with self._lock:
            return self._snapshots.get(symbol.upper())
