"""Deterministic reference implementations of Kuber's seven analyst contracts."""
from __future__ import annotations

from dataclasses import dataclass

from kuber.agents.base import AgentContext, Analyst
from kuber.models import AgentName, AgentResult, Bias


def _bias(score: float) -> Bias:
    if score > 10:
        return Bias.BULLISH
    if score < -10:
        return Bias.BEARISH
    return Bias.NEUTRAL


@dataclass(frozen=True)
class TechnicalAnalyst:
    name: AgentName = AgentName.TECHNICAL

    def analyze(self, context: AgentContext) -> AgentResult:
        quote = context.intelligence.quote
        score = 0.0 if quote.vwap is None else ((quote.last_price - quote.vwap) / quote.vwap) * 1000
        return AgentResult(
            self.name, _bias(score), min(90, int(abs(score) * 4 + 35)),
            (f"Spot {quote.last_price:.2f}", f"VWAP {quote.vwap:.2f}" if quote.vwap else "VWAP unavailable"),
            (), context.intelligence.version, quote.timestamp, {"score": round(score, 2)},
        )


@dataclass(frozen=True)
class FundamentalAnalyst:
    name: AgentName = AgentName.FUNDAMENTAL

    def analyze(self, context: AgentContext) -> AgentResult:
        quality = context.fundamentals.get("quality_score", 0.0)
        return AgentResult(
            self.name, _bias(quality), min(80, int(abs(quality) + 30)),
            (f"Fundamental quality score: {quality:.1f}",), (),
            context.intelligence.version, context.intelligence.quote.timestamp, {"score": quality},
        )


@dataclass(frozen=True)
class OptionsAnalyst:
    name: AgentName = AgentName.OPTIONS

    def analyze(self, context: AgentContext) -> AgentResult:
        snapshot = context.intelligence.snapshot
        score = 35.0 if snapshot.regime == "POSITIVE" else -35.0 if snapshot.regime == "NEGATIVE" else 0.0
        flip = "none" if snapshot.gamma_flip is None else f"{snapshot.gamma_flip:.2f}"
        return AgentResult(
            self.name, _bias(score), 85,
            (f"GEX regime: {snapshot.regime}", f"Gamma Flip: {flip}", f"Gamma walls: {list(snapshot.gamma_walls)}"),
            ("Negative gamma can amplify moves",) if snapshot.regime == "NEGATIVE" else (),
            context.intelligence.version, snapshot.timestamp,
            {"score": score, "gex_snapshot_id": snapshot.snapshot_id, "regime": snapshot.regime},
        )


@dataclass(frozen=True)
class NewsMacroAnalyst:
    name: AgentName = AgentName.NEWS_MACRO

    def analyze(self, context: AgentContext) -> AgentResult:
        score = context.news_bias
        return AgentResult(self.name, _bias(score), 45, (f"News/macro score: {score:.1f}",), (), context.intelligence.version, context.intelligence.quote.timestamp, {"score": score})


@dataclass(frozen=True)
class SentimentAnalyst:
    name: AgentName = AgentName.SENTIMENT

    def analyze(self, context: AgentContext) -> AgentResult:
        score = context.sentiment_score
        return AgentResult(self.name, _bias(score), 45, (f"Sentiment score: {score:.1f}",), (), context.intelligence.version, context.intelligence.quote.timestamp, {"score": score})


@dataclass(frozen=True)
class SectorRotationAnalyst:
    name: AgentName = AgentName.SECTOR_ROTATION

    def analyze(self, context: AgentContext) -> AgentResult:
        score = context.sector_score
        return AgentResult(self.name, _bias(score), 45, (f"Sector-flow score: {score:.1f}",), (), context.intelligence.version, context.intelligence.quote.timestamp, {"score": score})


@dataclass(frozen=True)
class RiskManagerAnalyst:
    name: AgentName = AgentName.RISK_MANAGER

    def analyze(self, context: AgentContext) -> AgentResult:
        snapshot = context.intelligence.snapshot
        stale = snapshot.is_stale(60)
        risks = ["GEX snapshot is stale"] if stale else []
        risks.append("Negative gamma requires reduced position size") if snapshot.regime == "NEGATIVE" else None
        return AgentResult(
            self.name, Bias.NEUTRAL, 100,
            ("Risk Manager is independent and may veto execution",), tuple(risks),
            context.intelligence.version, snapshot.timestamp,
            {"score": 0.0, "veto_candidate": stale},
        )


def default_agents() -> tuple[Analyst, ...]:
    return (
        TechnicalAnalyst(), FundamentalAnalyst(), OptionsAnalyst(), NewsMacroAnalyst(),
        SentimentAnalyst(), SectorRotationAnalyst(), RiskManagerAnalyst(),
    )
