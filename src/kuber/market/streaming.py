"""Thread-safe quote fan-out for broker streams and API WebSocket clients."""
from __future__ import annotations

from dataclasses import asdict
from queue import Queue
from threading import RLock

from kuber.models import Quote


class MarketStreamBus:
    """Retains the latest quote and fans updates out without blocking a broker SDK."""

    def __init__(self) -> None:
        self._latest: dict[str, Quote] = {}
        self._subscribers: dict[str, set[Queue[Quote]]] = {}
        self._lock = RLock()

    def publish(self, quote: Quote) -> None:
        with self._lock:
            self._latest[quote.symbol] = quote
            subscribers = tuple(self._subscribers.get(quote.symbol, set()))
        for subscriber in subscribers:
            subscriber.put(quote)

    def latest(self, symbol: str) -> Quote | None:
        with self._lock:
            return self._latest.get(symbol.upper())

    def subscribe(self, symbol: str) -> Queue[Quote]:
        subscriber: Queue[Quote] = Queue()
        with self._lock:
            self._subscribers.setdefault(symbol.upper(), set()).add(subscriber)
        return subscriber

    def unsubscribe(self, symbol: str, subscriber: Queue[Quote]) -> None:
        with self._lock:
            subscribers = self._subscribers.get(symbol.upper())
            if not subscribers:
                return
            subscribers.discard(subscriber)
            if not subscribers:
                self._subscribers.pop(symbol.upper(), None)

    @staticmethod
    def payload(quote: Quote) -> dict[str, object]:
        data = asdict(quote)
        data["timestamp"] = quote.timestamp.isoformat()
        return {"kind": "quote", "data": data}
