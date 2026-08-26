from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


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
        if self.lot_size <= 0 or self.tick_size <= 0:
            raise ValueError("lot_size and tick_size must be positive")
        if self.option_type not in {None, "CE", "PE"}:
            raise ValueError("option_type must be CE, PE or null")


@dataclass(frozen=True)
class InstrumentSearchResult:
    catalog_version: str
    as_of: datetime
    items: tuple[Instrument, ...]
