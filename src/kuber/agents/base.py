from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from kuber.models import AgentName, AgentResult, MarketIntelligence


@dataclass(frozen=True)
class AgentContext:
    intelligence: MarketIntelligence
    fundamentals: dict[str, float] = field(default_factory=dict)
    news_bias: float = 0.0
    sentiment_score: float = 0.0
    sector_score: float = 0.0


class Analyst(Protocol):
    name: AgentName

    def analyze(self, context: AgentContext) -> AgentResult: ...
