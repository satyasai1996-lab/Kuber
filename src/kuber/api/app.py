"""Versioned FastAPI contract for the Kuber Android client."""
from __future__ import annotations

from dataclasses import asdict
from datetime import datetime
from enum import Enum
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from kuber.agents.base import AgentContext
from kuber.agents.coordinator import AnalysisCoordinator
from kuber.brokers.mock import MockBroker
from kuber.execution.service import BrokerRegistry, ExecutionService
from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer
from kuber.models import OrderRequest, OrderSide, TradingMode


def _json(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(key.value if isinstance(key, Enum) else key): _json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json(item) for item in value]
    return value


class QuotePayload(BaseModel):
    last_price: float = Field(gt=0)
    timestamp: datetime | None = None
    volume: int | None = Field(default=None, ge=0)
    vwap: float | None = Field(default=None, gt=0)
    source: str = "mock"


class OptionPayload(BaseModel):
    strike: float = Field(gt=0)
    expiry: str
    option_type: str
    open_interest: int = Field(ge=0)
    implied_volatility: float = Field(ge=0)
    gamma: float = Field(ge=0)
    last_price: float = Field(ge=0)
    lot_size: int = Field(gt=0)
    volume: int = Field(default=0, ge=0)


class AnalysisRequest(BaseModel):
    symbol: str
    quote: QuotePayload
    options: list[OptionPayload]
    fundamentals: dict[str, float] = Field(default_factory=dict)
    news_bias: float = 0
    sentiment_score: float = 0
    sector_score: float = 0


class OrderPayload(BaseModel):
    broker: str = "mock"
    symbol: str
    side: OrderSide
    quantity: int = Field(gt=0)
    order_type: str = "MARKET"
    idempotency_key: str = Field(min_length=8)
    price: float | None = Field(default=None, gt=0)


class LiveOrderPayload(OrderPayload):
    confirmed: bool


class KuberServices:
    def __init__(self) -> None:
        self.normalizer = MarketDataNormalizer()
        self.intelligence = SharedMarketIntelligence()
        self.coordinator = AnalysisCoordinator()
        self.registry = BrokerRegistry()
        self.execution = ExecutionService(self.registry)
        self.analyses: dict[str, Any] = {}

    def prepare(self, request: AnalysisRequest):
        quote = self.normalizer.normalize_quote(request.symbol, request.quote.model_dump(), request.quote.source)
        options = self.normalizer.normalize_options(request.symbol, [item.model_dump() for item in request.options], request.quote.source)
        intelligence = self.intelligence.publish(quote, options)
        mock = MockBroker(quote=quote, option_chain=options)
        self.registry.register(mock)
        return intelligence


def create_app(services: KuberServices | None = None) -> FastAPI:
    app = FastAPI(title="Kuber API", version="v1", docs_url="/docs")
    app.state.services = services or KuberServices()

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "service": "kuber"}

    @app.post("/analysis/analyze")
    def analyze(request: AnalysisRequest) -> Any:
        intelligence = app.state.services.prepare(request)
        result = app.state.services.coordinator.analyze(AgentContext(
            intelligence=intelligence, fundamentals=request.fundamentals, news_bias=request.news_bias,
            sentiment_score=request.sentiment_score, sector_score=request.sector_score,
        ))
        app.state.services.analyses[result.analysis_id] = result
        return _json(asdict(result))

    @app.get("/analysis/{analysis_id}")
    def get_analysis(analysis_id: str) -> Any:
        result = app.state.services.analyses.get(analysis_id)
        if result is None:
            raise HTTPException(status_code=404, detail="analysis not found")
        return _json(asdict(result))

    @app.get("/analysis/gex/{symbol}")
    def get_gex(symbol: str) -> Any:
        intelligence = app.state.services.intelligence.get(symbol)
        if intelligence is None:
            raise HTTPException(status_code=404, detail="GEX snapshot not found; submit analysis input first")
        return _json(asdict(intelligence.snapshot))

    @app.get("/market/quote/{symbol}")
    def get_quote(symbol: str) -> Any:
        intelligence = app.state.services.intelligence.get(symbol)
        if intelligence is None:
            raise HTTPException(status_code=404, detail="quote not found")
        return _json(asdict(intelligence.quote))

    @app.get("/brokers")
    @app.get("/broker/status")
    def broker_status() -> Any:
        return app.state.services.registry.statuses()

    @app.get("/portfolio")
    def portfolio() -> Any:
        try:
            broker = app.state.services.registry.get("mock")
        except LookupError:
            return {"holdings": [], "positions": [], "funds": 0.0}
        return {"holdings": broker.get_holdings(), "positions": broker.get_positions(), "funds": broker.get_funds()}

    @app.post("/orders/paper")
    def paper_order(payload: OrderPayload) -> Any:
        request = OrderRequest(payload.broker, TradingMode.PAPER, payload.symbol.upper(), payload.side, payload.quantity, payload.order_type, payload.idempotency_key, payload.price)
        try:
            return _json(asdict(app.state.services.execution.submit_paper(request)))
        except (LookupError, ValueError) as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @app.post("/orders/live/confirm")
    def live_order(payload: LiveOrderPayload) -> Any:
        request = OrderRequest(payload.broker, TradingMode.LIVE, payload.symbol.upper(), payload.side, payload.quantity, payload.order_type, payload.idempotency_key, payload.price)
        try:
            return _json(asdict(app.state.services.execution.confirm_live(request, payload.confirmed)))
        except (LookupError, ValueError, PermissionError) as error:
            raise HTTPException(status_code=409, detail=str(error)) from error

    return app


app = create_app()
