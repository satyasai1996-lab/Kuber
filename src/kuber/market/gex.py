"""Validated, shared Gamma Exposure calculations."""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from typing import Iterable
from uuid import uuid4

from kuber.models import GexSnapshot, GexStrike, OptionContract, utc_now


class GexCalculator:
    """Build one timestamped GEX snapshot for every downstream consumer."""

    def build_snapshot(
        self,
        symbol: str,
        spot: float,
        contracts: Iterable[OptionContract],
        source: str,
        timestamp: datetime | None = None,
    ) -> GexSnapshot:
        if spot <= 0:
            raise ValueError("spot must be positive")
        if not source:
            raise ValueError("GEX source is required")
        chain = tuple(contracts)
        if not chain:
            raise ValueError("cannot calculate GEX without option contracts")
        if any(contract.underlying != symbol.upper() for contract in chain):
            raise ValueError("all option contracts must match the snapshot symbol")

        totals: dict[float, dict[str, float]] = defaultdict(lambda: {"CE": 0.0, "PE": 0.0})
        for contract in chain:
            exposure = contract.open_interest * contract.gamma * spot * contract.lot_size * 100
            # Dealer-short convention: calls contribute positive, puts negative exposure.
            totals[contract.strike][contract.option_type] += exposure if contract.option_type == "CE" else -exposure

        strikes = tuple(
            GexStrike(
                strike=strike,
                call_gex=round(values["CE"], 2),
                put_gex=round(values["PE"], 2),
                net_gex=round(values["CE"] + values["PE"], 2),
            )
            for strike, values in sorted(totals.items())
        )
        total_gex = round(sum(item.net_gex for item in strikes), 2)
        flip = self._find_flip(strikes)
        regime = "POSITIVE" if total_gex > 50 else "NEGATIVE" if total_gex < -50 else "NEUTRAL"
        walls = tuple(item.strike for item in sorted(strikes, key=lambda item: abs(item.net_gex), reverse=True)[:3])
        return GexSnapshot(
            snapshot_id=uuid4().hex,
            symbol=symbol.upper(),
            spot=spot,
            expiry_set=tuple(sorted({contract.expiry for contract in chain})),
            gex_by_strike=strikes,
            total_gex=total_gex,
            gamma_flip=flip,
            regime=regime,
            gamma_walls=walls,
            timestamp=timestamp or utc_now(),
            source=source,
        )

    @staticmethod
    def _find_flip(strikes: tuple[GexStrike, ...]) -> float | None:
        """Linearly interpolate the first net-GEX sign crossing."""
        for previous, current in zip(strikes, strikes[1:]):
            if previous.net_gex == 0:
                return previous.strike
            if previous.net_gex * current.net_gex < 0:
                ratio = abs(previous.net_gex) / (abs(previous.net_gex) + abs(current.net_gex))
                return round(previous.strike + ratio * (current.strike - previous.strike), 2)
        return None
