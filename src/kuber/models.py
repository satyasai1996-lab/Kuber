from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from math import isfinite
from typing import Any
from uuid import uuid4


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Bias(str, Enum):
    BULLISH = "BULLISH"
    BEARISH = "BEARISH"
    NEUTRAL = "NEUTRAL"
    UNAVAILABLE = "UNAVAILABLE"


class AgentName(str, Enum):
    TECHNICAL = "technical"
    FUNDAMENTAL = "fundamental"
    OPTIONS = "options"
    NEWS_MACRO = "news_macro"
    SENTIMENT = "sentiment"
    SECTOR_ROTATION = "sector_rotation"
    RISK_MANAGER = "risk_manager"


class TradingMode(str, Enum):
    PAPER = "PAPER"
    LIVE = "LIVE"


class OrderSide(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


@dataclass(frozen=True)
class Quote:
    symbol: str
    last_price: float
    timestamp: datetime
    source: str
    volume: int | None = None
    vwap: float | None = None

    def __post_init__(self) -> None:
        if self.last_price <= 0:
            raise ValueError("last_price must be positive")
        if not self.source:
            raise ValueError("quote source is required")


@dataclass(frozen=True)
class OptionContract:
    """One normalized option row; implied volatility is decimal (0.20 = 20%)."""

    underlying: str
    strike: float
    expiry: str
    option_type: str
    open_interest: int
    implied_volatility: float
    gamma: float
    last_price: float
    lot_size: int
    volume: int = 0

    def __post_init__(self) -> None:
        if self.option_type not in {"CE", "PE"}:
            raise ValueError("option_type must be CE or PE")
        if not isfinite(self.strike) or self.strike <= 0 or self.lot_size <= 0:
            raise ValueError("strike and lot_size must be positive")
        if not isfinite(self.implied_volatility) or not 0 < self.implied_volatility <= 5:
            raise ValueError("implied_volatility must be a decimal in (0, 5]")
        if not isfinite(self.gamma) or not isfinite(self.last_price):
            raise ValueError("option metrics must be finite")
        if self.open_interest < 0 or self.gamma < 0 or self.last_price < 0:
            raise ValueError("option metrics cannot be negative")


@dataclass(frozen=True)
class GexStrike:
    strike: float
    call_gex: float
    put_gex: float
    net_gex: float


@dataclass(frozen=True)
class GexSnapshot:
    snapshot_id: str
    symbol: str
    spot: float
    expiry_set: tuple[str, ...]
    gex_by_strike: tuple[GexStrike, ...]
    total_gex: float
    gamma_flip: float | None
    regime: str
    gamma_walls: tuple[float, ...]
    timestamp: datetime
    source: str

    def is_stale(self, max_age_seconds: int, now: datetime | None = None) -> bool:
        current = now or utc_now()
        return (current - self.timestamp).total_seconds() > max_age_seconds


@dataclass(frozen=True)
class MarketIntelligence:
    snapshot: GexSnapshot
    quote: Quote
    option_chain: tuple[OptionContract, ...]
    version: str = field(default_factory=lambda: uuid4().hex)


@dataclass(frozen=True)
class AgentResult:
    agent: AgentName
    bias: Bias
    confidence: int
    evidence: tuple[str, ...]
    risks: tuple[str, ...]
    intelligence_version: str
    input_timestamp: datetime
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Scorecard:
    scores: dict[AgentName, float]
    weights: dict[AgentName, float]
    weighted_score: float
    bias: Bias
    agreement_percent: float
    conflicts: tuple[str, ...]


@dataclass(frozen=True)
class Debate:
    bull_argument: str
    bear_argument: str
    facilitator_summary: str


@dataclass(frozen=True)
class TradePlan:
    direction: OrderSide | None
    entry: float | None
    stop_loss: float | None
    targets: tuple[float, ...]
    quantity: int
    risk_profile: str
    rationale: tuple[str, ...]
    gex_context: str


@dataclass(frozen=True)
class RiskDecision:
    approved: bool
    max_risk_amount: float
    quantity: int
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class AnalysisResult:
    analysis_id: str
    intelligence: MarketIntelligence
    agents: tuple[AgentResult, ...]
    scorecard: Scorecard
    debate: Debate
    trade_plans: tuple[TradePlan, ...]
    risk: RiskDecision
    final_bias: Bias
    created_at: datetime = field(default_factory=utc_now)


@dataclass(frozen=True)
class OrderRequest:
    broker: str
    mode: TradingMode
    symbol: str
    side: OrderSide
    quantity: int
    order_type: str
    idempotency_key: str
    price: float | None = None


@dataclass(frozen=True)
class OrderResponse:
    order_id: str
    status: str
    broker: str
    mode: TradingMode
    idempotency_key: str


@dataclass(frozen=True)
class AuditEvent:
    event_id: str
    action: str
    broker: str
    timestamp: datetime
    request_id: str
    result: str


@dataclass(frozen=True)
class Candle:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int = 0

    def __post_init__(self) -> None:
        if min(self.open, self.high, self.low, self.close) <= 0:
            raise ValueError("OHLC prices must be positive")
        if self.low > min(self.open, self.close) or self.high < max(self.open, self.close):
            raise ValueError("candle high/low does not contain open and close")


@dataclass(frozen=True)
class BotSignal:
    timestamp: datetime
    bias: Bias
    confidence: int
    risk_approved: bool
    rationale: tuple[str, ...]


@dataclass(frozen=True)
class BacktestTrade:
    entry_timestamp: datetime
    exit_timestamp: datetime
    side: OrderSide
    quantity: int
    entry_price: float
    exit_price: float
    net_pnl: float
    rationale: tuple[str, ...]


@dataclass(frozen=True)
class BacktestResult:
    initial_capital: float
    final_equity: float
    total_return_percent: float
    max_drawdown_percent: float
    win_rate_percent: float
    trades: tuple[BacktestTrade, ...]
    rejected_signals: int
