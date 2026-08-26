"""Validation bot for AI decisions before paper execution or backtesting."""
from __future__ import annotations

from dataclasses import dataclass

from kuber.models import AgentName, AnalysisResult, Bias, BotSignal


@dataclass(frozen=True)
class ValidationResult:
    valid: bool
    reasons: tuple[str, ...]


class AnalysisValidationBot:
    def validate(self, result: AnalysisResult) -> ValidationResult:
        reasons: list[str] = []
        names = {agent.agent for agent in result.agents}
        if names != set(AgentName):
            reasons.append("analysis must contain exactly the seven required agents")
        if any(agent.intelligence_version != result.intelligence.version for agent in result.agents):
            reasons.append("agents used incompatible market-intelligence versions")
        options = next((agent for agent in result.agents if agent.agent == AgentName.OPTIONS), None)
        if options is None or options.metadata.get("gex_snapshot_id") != result.intelligence.snapshot.snapshot_id:
            reasons.append("Options Analyst did not use the validated shared GEX snapshot")
        if not result.risk.approved and result.final_bias != Bias.NEUTRAL:
            reasons.append("Risk Manager veto must produce a neutral final decision")
        return ValidationResult(not reasons, tuple(reasons))

    def signal(self, result: AnalysisResult) -> BotSignal:
        validation = self.validate(result)
        if not validation.valid:
            raise ValueError("cannot backtest invalid analysis: " + "; ".join(validation.reasons))
        rationale = tuple(plan.rationale[0] for plan in result.trade_plans if plan.rationale)
        return BotSignal(
            timestamp=result.created_at,
            bias=result.final_bias,
            confidence=round(result.scorecard.agreement_percent),
            risk_approved=result.risk.approved,
            rationale=rationale,
        )
