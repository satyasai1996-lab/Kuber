"""Parallel seven-agent analysis, scorecard, debate and risk-vetted trade plans."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from uuid import uuid4

from kuber.agents.base import AgentContext, Analyst
from kuber.agents.default_agents import default_agents
from kuber.models import (
    AgentName, AgentResult, AnalysisResult, Bias, Debate, OrderSide, RiskDecision,
    Scorecard, TradePlan,
)
from kuber.risk.engine import RiskEngine


WEIGHTS = {
    AgentName.TECHNICAL: 0.25, AgentName.FUNDAMENTAL: 0.20, AgentName.OPTIONS: 0.15,
    AgentName.NEWS_MACRO: 0.10, AgentName.SENTIMENT: 0.10, AgentName.SECTOR_ROTATION: 0.05,
    AgentName.RISK_MANAGER: 0.15,
}


class AnalysisCoordinator:
    def __init__(self, agents: tuple[Analyst, ...] | None = None, risk_engine: RiskEngine | None = None) -> None:
        self.agents = agents or default_agents()
        if {agent.name for agent in self.agents} != set(AgentName):
            raise ValueError("Kuber requires exactly one implementation for each of the seven agents")
        self.risk_engine = risk_engine or RiskEngine()

    def analyze(self, context: AgentContext) -> AnalysisResult:
        with ThreadPoolExecutor(max_workers=7) as pool:
            results = tuple(pool.map(lambda agent: agent.analyze(context), self.agents))
        scorecard = self._scorecard(results)
        debate = self._debate(results, scorecard)
        plans = self._plans(context, scorecard)
        risk = self.risk_engine.evaluate(plans[1], context.intelligence.snapshot)
        plans = tuple(replace(plan, quantity=risk.quantity if risk.approved else 0) for plan in plans)
        final_bias = scorecard.bias if risk.approved else Bias.NEUTRAL
        return AnalysisResult(uuid4().hex, context.intelligence, results, scorecard, debate, plans, risk, final_bias)

    @staticmethod
    def _scorecard(results: tuple[AgentResult, ...]) -> Scorecard:
        direction = {Bias.BULLISH: 100.0, Bias.BEARISH: -100.0, Bias.NEUTRAL: 0.0, Bias.UNAVAILABLE: 0.0}
        scores = {result.agent: float(result.metadata.get("score", direction[result.bias])) for result in results}
        weighted_score = round(sum(scores[name] * WEIGHTS[name] for name in scores), 2)
        bias = Bias.BULLISH if weighted_score > 10 else Bias.BEARISH if weighted_score < -10 else Bias.NEUTRAL
        active = [result.bias for result in results if result.bias not in {Bias.NEUTRAL, Bias.UNAVAILABLE}]
        agreement = 0.0 if not active else round(max(active.count(Bias.BULLISH), active.count(Bias.BEARISH)) / len(active) * 100, 1)
        bulls = [result.agent.value for result in results if result.bias == Bias.BULLISH]
        bears = [result.agent.value for result in results if result.bias == Bias.BEARISH]
        conflicts = (f"Bullish: {', '.join(bulls)}; bearish: {', '.join(bears)}",) if bulls and bears else ()
        return Scorecard(scores, dict(WEIGHTS), weighted_score, bias, agreement, conflicts)

    @staticmethod
    def _debate(results: tuple[AgentResult, ...], scorecard: Scorecard) -> Debate:
        bull = [result.agent.value for result in results if result.bias == Bias.BULLISH]
        bear = [result.agent.value for result in results if result.bias == Bias.BEARISH]
        return Debate(
            bull_argument="Bull case supported by: " + (", ".join(bull) or "no analyst"),
            bear_argument="Bear case supported by: " + (", ".join(bear) or "no analyst"),
            facilitator_summary=f"Weighted score {scorecard.weighted_score:+.2f}; {scorecard.bias.value}; agreement {scorecard.agreement_percent:.1f}%.",
        )

    @staticmethod
    def _plans(context: AgentContext, scorecard: Scorecard) -> tuple[TradePlan, TradePlan, TradePlan]:
        quote = context.intelligence.quote
        direction = OrderSide.BUY if scorecard.bias == Bias.BULLISH else OrderSide.SELL if scorecard.bias == Bias.BEARISH else None
        if direction is None:
            plan = TradePlan(None, None, None, (), 0, "neutral", ("No directional consensus",), context.intelligence.snapshot.regime)
            return (plan, plan, plan)
        sign = 1 if direction == OrderSide.BUY else -1
        def make(profile: str, stop_pct: float, target_pct: float) -> TradePlan:
            return TradePlan(
                direction, quote.last_price, round(quote.last_price * (1 - sign * stop_pct), 2),
                (round(quote.last_price * (1 + sign * target_pct), 2),), 0, profile,
                (f"Scorecard: {scorecard.bias.value}",), context.intelligence.snapshot.regime,
            )
        return (make("aggressive", 0.01, 0.02), make("neutral", 0.02, 0.04), make("conservative", 0.03, 0.06))
