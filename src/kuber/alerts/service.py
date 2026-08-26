"""User-visible alert rules; evaluation stays server-side."""
from __future__ import annotations

from dataclasses import dataclass
from uuid import uuid4


@dataclass(frozen=True)
class AlertRule:
    alert_id: str
    symbol: str
    kind: str
    condition: str
    enabled: bool = True


class AlertStore:
    VALID_KINDS = {"PRICE", "GEX_REGIME", "GAMMA_FLIP", "OPTIONS_ANOMALY", "AI_DECISION"}

    def __init__(self) -> None:
        self._rules: dict[str, AlertRule] = {}

    def create(self, symbol: str, kind: str, condition: str) -> AlertRule:
        kind = kind.upper()
        if kind not in self.VALID_KINDS:
            raise ValueError(f"unsupported alert kind: {kind}")
        if not condition.strip():
            raise ValueError("alert condition is required")
        rule = AlertRule(uuid4().hex, symbol.upper(), kind, condition.strip())
        self._rules[rule.alert_id] = rule
        return rule

    def list(self) -> tuple[AlertRule, ...]:
        return tuple(self._rules.values())
