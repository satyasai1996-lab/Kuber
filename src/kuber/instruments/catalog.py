from __future__ import annotations

import hashlib
import json
from datetime import date, datetime, timezone
from threading import RLock
from typing import Iterable, Mapping, Any

from .models import Instrument, InstrumentSearchResult


def _expiry(value: Any) -> str | None:
    if value in (None, ""):
        return None
    if isinstance(value, (datetime, date)):
        return value.date().isoformat() if isinstance(value, datetime) else value.isoformat()
    return str(value)[:10]


def normalize_zerodha_instruments(rows: Iterable[Mapping[str, Any]]) -> tuple[Instrument, ...]:
    """Normalize a current Kite instrument master without inventing market values."""
    normalized: list[Instrument] = []
    for row in rows:
        try:
            exchange = str(row["exchange"]).strip().upper()
            segment = str(row["segment"]).strip().upper()
            symbol = str(row["tradingsymbol"]).strip().upper()
            token = str(row["instrument_token"]).strip()
            kind = str(row["instrument_type"]).strip().upper()
            name = str(row.get("name") or symbol).strip()
            strike_value = float(row.get("strike") or 0)
            normalized.append(Instrument(
                instrument_id=f"zerodha:{token}",
                provider="zerodha",
                provider_token=token,
                exchange=exchange,
                segment=segment,
                tradingsymbol=symbol,
                display_name=name,
                instrument_type=kind,
                underlying=name.upper() or None,
                expiry=_expiry(row.get("expiry")),
                strike=strike_value if strike_value > 0 else None,
                option_type=kind if kind in {"CE", "PE"} else None,
                lot_size=int(row.get("lot_size") or 1),
                tick_size=float(row.get("tick_size") or 0.05),
            ))
        except (KeyError, TypeError, ValueError):
            continue
    return tuple(normalized)


class InstrumentCatalog:
    """Atomic, searchable snapshot of provider instrument masters."""

    def __init__(self) -> None:
        self._lock = RLock()
        self._items: tuple[Instrument, ...] = ()
        self._version = hashlib.sha256(b"empty").hexdigest()
        self._as_of = datetime.now(timezone.utc)

    def replace(self, items: Iterable[Instrument], *, as_of: datetime | None = None) -> str:
        snapshot = tuple(items)
        if not snapshot:
            raise ValueError("instrument master cannot be empty")
        ids = [item.instrument_id for item in snapshot]
        if len(ids) != len(set(ids)):
            raise ValueError("instrument master contains duplicate instrument_id values")
        encoded = json.dumps(
            [(item.instrument_id, item.exchange, item.segment, item.tradingsymbol, item.expiry, item.strike) for item in snapshot],
            separators=(",", ":"),
            sort_keys=False,
        ).encode()
        with self._lock:
            self._items = snapshot
            self._version = hashlib.sha256(encoded).hexdigest()
            self._as_of = as_of or datetime.now(timezone.utc)
            return self._version

    def search(
        self,
        query: str,
        *,
        exchanges: set[str] | None = None,
        instrument_types: set[str] | None = None,
        limit: int = 25,
    ) -> InstrumentSearchResult:
        if not 1 <= limit <= 100:
            raise ValueError("limit must be between 1 and 100")
        term = query.strip().upper()
        exchange_filter = {item.upper() for item in exchanges or set()}
        type_filter = {item.upper() for item in instrument_types or set()}
        with self._lock:
            matches = [item for item in self._items if (
                (not exchange_filter or item.exchange in exchange_filter or item.segment in exchange_filter)
                and (not type_filter or item.instrument_type in type_filter)
                and (not term or any(term in value for value in (
                    item.tradingsymbol.upper(), item.display_name.upper(), (item.underlying or "").upper(),
                    item.exchange, item.segment,
                )))
            )]
            matches.sort(key=lambda item: (
                not item.tradingsymbol.startswith(term),
                item.exchange,
                item.tradingsymbol,
                item.expiry or "",
                item.strike or 0,
            ))
            return InstrumentSearchResult(self._version, self._as_of, tuple(matches[:limit]))
