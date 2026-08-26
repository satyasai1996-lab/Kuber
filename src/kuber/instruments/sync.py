from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Protocol

from .catalog import (
    ConcurrentCatalogUpdateError,
    InstrumentCatalog,
    InstrumentMasterValidationError,
    normalize_zerodha_instruments,
)
from .models import InstrumentSyncResult, SUPPORTED_EXCHANGES


class InstrumentMasterConnector(Protocol):
    def instrument_master(self) -> Iterable[Mapping[str, Any]]: ...


class InstrumentSyncError(RuntimeError):
    """A provider master could not be loaded or atomically published."""


class UnusableInstrumentMasterError(InstrumentSyncError):
    """A loaded provider snapshot failed completeness or identity validation."""


@dataclass(frozen=True)
class InstrumentSyncPolicy:
    required_exchanges: frozenset[str] = field(default_factory=lambda: SUPPORTED_EXCHANGES)
    minimum_items: int = 5
    minimum_retained_fraction: float = 0.5

    def __post_init__(self) -> None:
        unsupported = self.required_exchanges - SUPPORTED_EXCHANGES
        if unsupported:
            raise ValueError(f"unsupported required exchanges: {sorted(unsupported)}")
        if self.minimum_items <= 0:
            raise ValueError("minimum_items must be positive")
        if not 0 < self.minimum_retained_fraction <= 1:
            raise ValueError("minimum_retained_fraction must be in (0, 1]")


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class InstrumentCatalogSynchronizer:
    """Loads a provider master and publishes it only after complete validation."""

    def __init__(
        self,
        catalog: InstrumentCatalog,
        *,
        policy: InstrumentSyncPolicy | None = None,
        clock: Callable[[], datetime] = _utc_now,
    ) -> None:
        self._catalog = catalog
        self._policy = policy or InstrumentSyncPolicy()
        self._clock = clock

    def sync_zerodha(self, connector: InstrumentMasterConnector) -> InstrumentSyncResult:
        baseline = self._catalog.status()
        try:
            rows = connector.instrument_master()
        except Exception as error:
            message = f"Zerodha instrument-master load failed ({type(error).__name__})"
            self._catalog.record_sync_failure(message, expected_version=baseline.version)
            raise InstrumentSyncError(message) from error

        try:
            items = normalize_zerodha_instruments(rows)
        except InstrumentMasterValidationError as error:
            self._catalog.record_sync_failure(str(error), expected_version=baseline.version)
            raise UnusableInstrumentMasterError(str(error)) from error

        reasons: list[str] = []
        if len(items) < self._policy.minimum_items:
            reasons.append(
                f"snapshot contains {len(items)} instruments; minimum is {self._policy.minimum_items}"
            )
        exchanges = {item.exchange for item in items}
        missing = self._policy.required_exchanges - exchanges
        if missing:
            reasons.append(f"snapshot is missing required exchanges: {', '.join(sorted(missing))}")
        if (
            baseline.ready
            and baseline.source == "zerodha"
            and len(items) < baseline.item_count * self._policy.minimum_retained_fraction
        ):
            reasons.append(
                f"snapshot shrank from {baseline.item_count} to {len(items)} instruments, "
                f"below retained fraction {self._policy.minimum_retained_fraction:.2f}"
            )
        if reasons:
            message = "unusable Zerodha instrument master: " + "; ".join(reasons)
            self._catalog.record_sync_failure(message, expected_version=baseline.version)
            raise UnusableInstrumentMasterError(message)

        try:
            version = self._catalog.replace(
                items,
                source="zerodha",
                as_of=self._clock(),
                expected_version=baseline.version,
            )
        except ConcurrentCatalogUpdateError as error:
            raise InstrumentSyncError("instrument catalog changed before Zerodha publication") from error
        except (TypeError, ValueError) as error:
            message = f"Zerodha instrument-master publication failed: {error}"
            self._catalog.record_sync_failure(message, expected_version=baseline.version)
            raise InstrumentSyncError(message) from error

        return InstrumentSyncResult(
            status=self._catalog.status(),
            imported_count=len(items),
            unchanged=baseline.ready and baseline.version == version,
        )
