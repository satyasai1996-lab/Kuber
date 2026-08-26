"""Translate provider payloads into Kuber's internal market schema."""
from __future__ import annotations

from datetime import datetime
from math import isfinite
from typing import Any, Iterable

from kuber.models import OptionContract, Quote, utc_now


def _iv_decimal(item: dict[str, Any]) -> float:
    """Normalize legacy percentage payloads while keeping the core decimal-only.

    Values above 5 cannot be valid decimal volatility under Kuber's supported
    range, so treating them as percentages is unambiguous. New producers should
    send ``implied_volatility_decimal`` or a decimal ``implied_volatility``.
    """
    if item.get("implied_volatility_decimal") is not None:
        value = float(item["implied_volatility_decimal"])
    else:
        raw = item.get("implied_volatility", item.get("iv"))
        if raw is None:
            raise ValueError("option payload must include implied volatility")
        value = float(raw)
        if value > 5.0:
            value /= 100.0
    if not isfinite(value) or not 0 < value <= 5.0:
        raise ValueError("implied volatility must normalize to a decimal in (0, 5]")
    return value


class MarketDataNormalizer:
    """The only boundary where broker-specific market payloads are accepted."""

    def normalize_quote(self, symbol: str, payload: dict[str, Any], source: str) -> Quote:
        price = payload.get("last_price", payload.get("ltp", payload.get("last_traded_price")))
        if price is None:
            raise ValueError("quote payload must include last_price, ltp, or last_traded_price")
        raw_time = payload.get("timestamp")
        timestamp = datetime.fromisoformat(raw_time) if isinstance(raw_time, str) else raw_time or utc_now()
        return Quote(
            symbol=symbol.upper(),
            last_price=float(price),
            timestamp=timestamp,
            source=source,
            volume=int(payload["volume"]) if payload.get("volume") is not None else None,
            vwap=float(payload["vwap"]) if payload.get("vwap") is not None else None,
        )

    def normalize_options(
        self, underlying: str, payloads: Iterable[dict[str, Any]], source: str
    ) -> tuple[OptionContract, ...]:
        if not source:
            raise ValueError("options source is required")
        contracts: list[OptionContract] = []
        for item in payloads:
            contracts.append(
                OptionContract(
                    underlying=underlying.upper(),
                    strike=float(item["strike"]),
                    expiry=str(item["expiry"]),
                    option_type=str(item.get("option_type", item.get("type", ""))).upper(),
                    open_interest=int(item.get("open_interest", item.get("oi", 0))),
                    implied_volatility=_iv_decimal(item),
                    gamma=float(item["gamma"]),
                    last_price=float(item.get("last_price", item.get("ltp", 0))),
                    lot_size=int(item["lot_size"]),
                    volume=int(item.get("volume", 0)),
                )
            )
        if not contracts:
            raise ValueError("option chain cannot be empty")
        return tuple(contracts)
