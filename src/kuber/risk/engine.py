"""Risk controls with explicit veto authority."""
from __future__ import annotations

from dataclasses import dataclass

from kuber.models import GexSnapshot, RiskDecision, TradePlan


@dataclass(frozen=True)
class RiskLimits:
    capital: float = 200_000.0
    max_risk_percent: float = 2.0
    gex_max_age_seconds: int = 60


class RiskEngine:
    def __init__(self, limits: RiskLimits | None = None) -> None:
        self.limits = limits or RiskLimits()

    def evaluate(self, plan: TradePlan, snapshot: GexSnapshot) -> RiskDecision:
        max_risk = round(self.limits.capital * self.limits.max_risk_percent / 100, 2)
        reasons: list[str] = []
        if snapshot.is_stale(self.limits.gex_max_age_seconds):
            reasons.append("validated GEX snapshot is stale")
        if plan.direction is None or plan.entry is None or plan.stop_loss is None:
            reasons.append("no executable trade direction")
        if plan.entry is not None and plan.stop_loss is not None and plan.entry == plan.stop_loss:
            reasons.append("stop-loss cannot equal entry")
        if reasons:
            return RiskDecision(False, max_risk, 0, tuple(reasons))

        per_unit_risk = abs(plan.entry - plan.stop_loss)
        quantity = max(1, int(max_risk // per_unit_risk))
        if snapshot.regime == "NEGATIVE":
            quantity = max(1, quantity // 2)
            reasons.append("size reduced for negative-gamma regime")
        return RiskDecision(True, max_risk, quantity, tuple(reasons))
