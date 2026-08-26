from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable, Mapping
from dataclasses import asdict
from datetime import date, datetime, timezone
from math import isfinite
from threading import RLock
from typing import Any
from urllib.parse import quote

from .models import Instrument, InstrumentCatalogStatus, InstrumentSearchResult, SUPPORTED_EXCHANGES


_ANY_VERSION = object()
_EXCHANGE_ORDER = {exchange: index for index, exchange in enumerate(("NSE", "NFO", "BSE", "BFO", "MCX"))}
_INSTRUMENT_TYPES = frozenset({"EQ", "INDEX", "FUT", "CE", "PE"})


class InstrumentMasterValidationError(ValueError):
    """The provider master cannot be published without losing identity integrity."""

    def __init__(self, reasons: Iterable[str]) -> None:
        self.reasons = tuple(reasons)
        super().__init__("invalid Zerodha instrument master: " + "; ".join(self.reasons))


class ConcurrentCatalogUpdateError(RuntimeError):
    """A newer catalog won the compare-and-swap publication race."""


def _expiry(value: Any) -> str | None:
    if value in (None, ""):
        return None
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if not isinstance(value, str):
        raise ValueError("expiry must be an ISO date")
    try:
        return date.fromisoformat(value.strip()).isoformat()
    except ValueError as error:
        raise ValueError("expiry must be an ISO date") from error


def _required_text(row: Mapping[str, Any], field: str) -> str:
    if field not in row or row[field] is None or isinstance(row[field], bool):
        raise ValueError(f"{field} is required")
    value = str(row[field]).strip()
    if not value:
        raise ValueError(f"{field} is required")
    return value


def _positive_integer(value: Any, field: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"{field} must be a positive integer")
    try:
        number = int(value)
        numeric = float(value)
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError(f"{field} must be a positive integer") from error
    if number <= 0 or not isfinite(numeric) or numeric != number:
        raise ValueError(f"{field} must be a positive integer")
    return number


def _number(value: Any, field: str, *, positive: bool) -> float:
    if isinstance(value, bool):
        qualifier = "positive" if positive else "non-negative"
        raise ValueError(f"{field} must be {qualifier} and finite")
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError) as error:
        qualifier = "positive" if positive else "non-negative"
        raise ValueError(f"{field} must be {qualifier} and finite") from error
    invalid_sign = number <= 0 if positive else number < 0
    if not isfinite(number) or invalid_sign:
        qualifier = "positive" if positive else "non-negative"
        raise ValueError(f"{field} must be {qualifier} and finite")
    return number


def _validate_segment(exchange: str, segment: str) -> None:
    valid = segment == exchange or segment.startswith(f"{exchange}-")
    if exchange in {"NSE", "BSE"} and segment == "INDICES":
        valid = True
    if not valid:
        raise ValueError(f"segment {segment!r} does not belong to exchange {exchange!r}")


def _normalize_zerodha_row(row: Mapping[str, Any]) -> Instrument:
    exchange = _required_text(row, "exchange").upper()
    if exchange not in SUPPORTED_EXCHANGES:
        raise ValueError(f"unsupported exchange {exchange!r}")
    segment = _required_text(row, "segment").upper()
    _validate_segment(exchange, segment)
    symbol = _required_text(row, "tradingsymbol").upper()
    token = str(_positive_integer(row.get("instrument_token"), "instrument_token"))
    kind = _required_text(row, "instrument_type").upper()
    if kind not in _INSTRUMENT_TYPES:
        raise ValueError(f"unsupported instrument_type {kind!r}")

    lot_size = _positive_integer(row.get("lot_size"), "lot_size")
    tick_size = _number(row.get("tick_size"), "tick_size", positive=True)
    strike_value = _number(row.get("strike", 0), "strike", positive=False)
    expiry = _expiry(row.get("expiry"))
    name = str(row.get("name") or symbol).strip()
    if not name:
        name = symbol

    if kind in {"CE", "PE"}:
        if expiry is None:
            raise ValueError(f"{kind} contract requires expiry")
        if strike_value <= 0:
            raise ValueError(f"{kind} contract requires a positive strike")
    elif kind == "FUT" and expiry is None:
        raise ValueError("FUT contract requires expiry")
    elif strike_value != 0:
        raise ValueError(f"{kind} instrument must not carry a strike")

    return Instrument(
        instrument_id=f"kuber:{exchange.lower()}:{quote(symbol.lower(), safe='')}",
        provider="zerodha",
        provider_token=token,
        exchange=exchange,
        segment=segment,
        tradingsymbol=symbol,
        display_name=name,
        instrument_type=kind,
        underlying=name.upper() or None,
        expiry=expiry,
        strike=strike_value if strike_value > 0 else None,
        option_type=kind if kind in {"CE", "PE"} else None,
        lot_size=lot_size,
        tick_size=tick_size,
    )


def normalize_zerodha_instruments(rows: Iterable[Mapping[str, Any]]) -> tuple[Instrument, ...]:
    """Validate and normalize one complete Kite instrument-master snapshot.

    This function is deliberately all-or-nothing. Silently skipping a malformed
    provider row would create a plausible-looking but incomplete trading universe.
    """

    normalized: list[Instrument] = []
    reasons: list[str] = []
    try:
        iterator = iter(rows)
    except TypeError as error:
        raise InstrumentMasterValidationError(("snapshot is not iterable",)) from error

    try:
        for index, row in enumerate(iterator):
            if not isinstance(row, Mapping):
                reasons.append(f"row {index}: expected a mapping")
                continue
            try:
                normalized.append(_normalize_zerodha_row(row))
            except (KeyError, TypeError, ValueError) as error:
                reasons.append(f"row {index}: {error}")
    except Exception as error:
        reasons.append(f"provider snapshot iteration failed: {type(error).__name__}")

    instrument_ids: set[str] = set()
    provider_identities: set[tuple[str, str]] = set()
    tradable_identities: set[tuple[str, str]] = set()
    for item in normalized:
        if item.instrument_id in instrument_ids:
            reasons.append(f"duplicate canonical identity {item.instrument_id}")
        instrument_ids.add(item.instrument_id)
        provider_identity = (item.provider, item.provider_token)
        if provider_identity in provider_identities:
            reasons.append(f"duplicate provider token {item.provider_token}")
        provider_identities.add(provider_identity)
        identity = (item.exchange, item.tradingsymbol)
        if identity in tradable_identities:
            reasons.append(f"duplicate tradable identity {item.exchange}:{item.tradingsymbol}")
        tradable_identities.add(identity)

    if reasons:
        raise InstrumentMasterValidationError(reasons)
    return tuple(normalized)


class InstrumentCatalog:
    """Thread-safe, atomic, searchable snapshot of provider instrument masters."""

    def __init__(self) -> None:
        self._lock = RLock()
        self._items: tuple[Instrument, ...] = ()
        self._version: str | None = None
        self._as_of: datetime | None = None
        self._source: str | None = None
        self._last_error: str | None = None

    @property
    def ready(self) -> bool:
        return self.status().ready

    @property
    def source(self) -> str | None:
        return self.status().source

    @property
    def as_of(self) -> datetime | None:
        return self.status().as_of

    @property
    def version(self) -> str | None:
        return self.status().version

    def status(self) -> InstrumentCatalogStatus:
        with self._lock:
            exchanges = tuple(sorted(
                {item.exchange for item in self._items},
                key=lambda exchange: (_EXCHANGE_ORDER.get(exchange, len(_EXCHANGE_ORDER)), exchange),
            ))
            return InstrumentCatalogStatus(
                ready=bool(self._items and self._version and self._as_of and self._source),
                source=self._source,
                as_of=self._as_of,
                version=self._version,
                item_count=len(self._items),
                exchanges=exchanges,
                last_error=self._last_error,
            )

    def replace(
        self,
        items: Iterable[Instrument],
        *,
        source: str | None = None,
        as_of: datetime | None = None,
        expected_version: str | None | object = _ANY_VERSION,
    ) -> str:
        snapshot = tuple(items)
        if not snapshot:
            raise ValueError("instrument master cannot be empty")
        if any(not isinstance(item, Instrument) for item in snapshot):
            raise TypeError("instrument master must contain only Instrument values")

        ids = [item.instrument_id for item in snapshot]
        if len(ids) != len(set(ids)):
            raise ValueError("instrument master contains duplicate instrument_id values")
        identities = [(item.provider, item.exchange, item.tradingsymbol) for item in snapshot]
        if len(identities) != len(set(identities)):
            raise ValueError("instrument master contains duplicate provider/exchange/tradingsymbol values")

        providers = {item.provider for item in snapshot}
        normalized_source = (source or (next(iter(providers)) if len(providers) == 1 else "manual")).strip().lower()
        if not normalized_source:
            raise ValueError("catalog source is required")
        if source is not None and providers != {normalized_source}:
            raise ValueError("catalog source does not match every instrument provider")

        captured_at = as_of or datetime.now(timezone.utc)
        if captured_at.tzinfo is None or captured_at.utcoffset() is None:
            raise ValueError("catalog as_of must be timezone-aware")
        captured_at = captured_at.astimezone(timezone.utc)

        canonical = tuple(sorted(snapshot, key=lambda item: item.instrument_id))
        encoded = json.dumps(
            [asdict(item) for item in canonical],
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        version = hashlib.sha256(encoded).hexdigest()

        with self._lock:
            if expected_version is not _ANY_VERSION and self._version != expected_version:
                if self._version == version and self._source == normalized_source:
                    self._last_error = None
                    return version
                raise ConcurrentCatalogUpdateError("instrument catalog changed while this snapshot was loading")
            if self._version == version:
                self._last_error = None
                return version
            self._items = canonical
            self._version = version
            self._as_of = captured_at
            self._source = normalized_source
            self._last_error = None
            return version

    def record_sync_failure(self, message: str, *, expected_version: str | None | object = _ANY_VERSION) -> None:
        safe_message = message.strip() or "instrument catalog synchronization failed"
        with self._lock:
            if expected_version is _ANY_VERSION or self._version == expected_version:
                self._last_error = safe_message

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
        requested_exchanges = {item.upper() for item in exchanges or set()}
        exchange_filter = set(requested_exchanges)
        if "NSE" in requested_exchanges:
            exchange_filter.add("NFO")
        if "BSE" in requested_exchanges:
            exchange_filter.add("BFO")
        type_filter = {item.upper() for item in instrument_types or set()}
        with self._lock:
            snapshot = self._items
            version = self._version
            as_of = self._as_of
            source = self._source
        matches = [item for item in snapshot if (
            (not exchange_filter or item.exchange in exchange_filter or item.segment in exchange_filter)
            and (not type_filter or item.instrument_type in type_filter)
            and (not term or any(term in value for value in (
                item.tradingsymbol.upper(), item.display_name.upper(), (item.underlying or "").upper(),
                item.exchange, item.segment,
            )))
        )]
        matches.sort(key=lambda item: (
            not item.tradingsymbol.startswith(term),
            _EXCHANGE_ORDER.get(item.exchange, len(_EXCHANGE_ORDER)),
            item.tradingsymbol,
            item.expiry or "",
            item.strike or 0,
        ))
        ready = bool(snapshot and version and as_of and source)
        return InstrumentSearchResult(version, as_of, tuple(matches[:limit]), ready, source)
