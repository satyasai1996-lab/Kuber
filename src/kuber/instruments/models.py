from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from math import isfinite


SUPPORTED_EXCHANGES = frozenset({"NSE", "NFO", "BSE", "BFO", "MCX"})


@dataclass(frozen=True)
class Instrument:
    instrument_id: str
    provider: str
    provider_token: str
    exchange: str
    segment: str
    tradingsymbol: str
    display_name: str
    instrument_type: str
    underlying: str | None
    expiry: str | None
    strike: float | None
    option_type: str | None
    lot_size: int
    tick_size: float
    currency: str = "INR"

    def __post_init__(self) -> None:
        if not all((self.instrument_id, self.provider, self.provider_token, self.exchange, self.segment, self.tradingsymbol)):
            raise ValueError("instrument identity fields are required")
        if self.exchange not in SUPPORTED_EXCHANGES:
            raise ValueError(f"unsupported instrument exchange: {self.exchange}")
        if self.lot_size <= 0 or self.tick_size <= 0:
            raise ValueError("lot_size and tick_size must be positive")
        if not isfinite(self.tick_size):
            raise ValueError("tick_size must be finite")
        if self.strike is not None and (not isfinite(self.strike) or self.strike <= 0):
            raise ValueError("strike must be finite and positive when present")
        if self.option_type not in {None, "CE", "PE"}:
            raise ValueError("option_type must be CE, PE or null")

    def to_public_dict(self) -> dict[str, object]:
        """Return the mobile-safe identity and contract metadata.

        Provider names and provider tokens are deliberately excluded. They are
        backend routing details and must not cross the Android API boundary.
        """
        return {
            "instrument_id": self.instrument_id,
            "exchange": self.exchange,
            "segment": self.segment,
            "tradingsymbol": self.tradingsymbol,
            "display_name": self.display_name,
            "instrument_type": self.instrument_type,
            "underlying": self.underlying,
            "expiry": self.expiry,
            "strike": self.strike,
            "option_type": self.option_type,
            "lot_size": self.lot_size,
            "tick_size": self.tick_size,
            "currency": self.currency,
        }


@dataclass(frozen=True)
class InstrumentSearchResult:
    catalog_version: str | None
    as_of: datetime | None
    items: tuple[Instrument, ...]
    ready: bool = False
    source: str | None = None


@dataclass(frozen=True)
class InstrumentCatalogStatus:
    ready: bool
    source: str | None
    as_of: datetime | None
    version: str | None
    item_count: int
    exchanges: tuple[str, ...]
    last_error: str | None = None


@dataclass(frozen=True)
class InstrumentSyncResult:
    status: InstrumentCatalogStatus
    imported_count: int
    unchanged: bool
