"""Versioned FastAPI contract for the Kuber Android client."""
from __future__ import annotations

from dataclasses import asdict
from datetime import datetime
from enum import Enum
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from kuber.agents.base import AgentContext
from kuber.agents.coordinator import AnalysisCoordinator
from kuber.alerts.service import AlertStore
from kuber.backtest.engine import BacktestConfig, BotSignalBacktester
from kuber.brokers.mock import MockBroker
from kuber.brokers.connection import BrokerConnectionService
from kuber.config import KuberSettings
from kuber.execution.service import BrokerRegistry, ExecutionService
from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer
from kuber.models import Bias, BotSignal, Candle, OrderRequest, OrderSide, TradingMode


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


class CandlePayload(BaseModel):
    timestamp: datetime
    open: float = Field(gt=0)
    high: float = Field(gt=0)
    low: float = Field(gt=0)
    close: float = Field(gt=0)
    volume: int = Field(default=0, ge=0)


class BotSignalPayload(BaseModel):
    timestamp: datetime
    bias: Bias
    confidence: int = Field(ge=0, le=100)
    risk_approved: bool
    rationale: list[str] = Field(default_factory=list)


class BacktestPayload(BaseModel):
    candles: list[CandlePayload] = Field(min_length=2)
    signals: list[BotSignalPayload]
    initial_capital: float = Field(default=200_000, gt=0)
    allocation_percent: float = Field(default=25, gt=0, le=100)
    transaction_cost_bps: float = Field(default=10, ge=0)


class AlertPayload(BaseModel):
    symbol: str
    kind: str
    condition: str


class BrokerConnectPayload(BaseModel):
    broker: str
    credentials: dict[str, str]


class KuberServices:
    def __init__(self) -> None:
        self.normalizer = MarketDataNormalizer()
        self.intelligence = SharedMarketIntelligence()
        self.coordinator = AnalysisCoordinator()
        self.registry = BrokerRegistry()
        self.execution = ExecutionService(self.registry)
        self.analyses: dict[str, Any] = {}
        self.alerts = AlertStore()
        self.broker_connections = BrokerConnectionService()

    def prepare(self, request: AnalysisRequest):
        quote = self.normalizer.normalize_quote(request.symbol, request.quote.model_dump(), request.quote.source)
        options = self.normalizer.normalize_options(request.symbol, [item.model_dump() for item in request.options], request.quote.source)
        intelligence = self.intelligence.publish(quote, options)
        mock = MockBroker(quote=quote, option_chain=options)
        self.registry.register(mock)
        return intelligence


def create_app(services: KuberServices | None = None, settings: KuberSettings | None = None) -> FastAPI:
    app = FastAPI(title="Kuber API", version="v1", docs_url="/docs")
    app.state.services = services or KuberServices()
    app.state.settings = settings or KuberSettings.from_environment()

    @app.middleware("http")
    async def token_auth(request: Request, call_next):
        # A development install may run without a token. Any configured token protects
        # every data/execution endpoint; Android never receives broker credentials.
        token = app.state.settings.api_token
        if token and request.url.path not in {"/health", "/docs", "/openapi.json"}:
            if request.headers.get("Authorization") != f"Bearer {token}":
                return JSONResponse({"detail": "unauthorized"}, status_code=401)
        return await call_next(request)

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

    @app.get("/options/chain/{symbol}")
    def option_chain(symbol: str) -> Any:
        intelligence = app.state.services.intelligence.get(symbol)
        if intelligence is None:
            raise HTTPException(status_code=404, detail="option chain not found")
        return _json([asdict(contract) for contract in intelligence.option_chain])

    @app.get("/analysis/iv-smile/{symbol}")
    def iv_smile(symbol: str) -> Any:
        intelligence = app.state.services.intelligence.get(symbol)
        if intelligence is None:
            raise HTTPException(status_code=404, detail="option chain not found")
        return _json({"symbol": symbol.upper(), "points": [
            {"strike": contract.strike, "option_type": contract.option_type, "implied_volatility": contract.implied_volatility}
            for contract in intelligence.option_chain
        ]})

    @app.post("/backtest")
    def backtest(payload: BacktestPayload) -> Any:
        candles = tuple(Candle(**item.model_dump()) for item in payload.candles)
        signals = tuple(BotSignal(item.timestamp, item.bias, item.confidence, item.risk_approved, tuple(item.rationale)) for item in payload.signals)
        try:
            result = BotSignalBacktester(BacktestConfig(payload.initial_capital, payload.allocation_percent, payload.transaction_cost_bps)).run(candles, signals)
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        return _json(asdict(result))

    @app.get("/alerts")
    def list_alerts() -> Any:
        return _json([asdict(rule) for rule in app.state.services.alerts.list()])

    @app.post("/alerts")
    def create_alert(payload: AlertPayload) -> Any:
        try:
            return _json(asdict(app.state.services.alerts.create(payload.symbol, payload.kind, payload.condition)))
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @app.get("/brokers")
    @app.get("/broker/status")
    def broker_status() -> Any:
        return app.state.services.registry.statuses()

    @app.post("/brokers/connect")
    def connect_broker(payload: BrokerConnectPayload) -> Any:
        # Do not log this payload. The connection service clears it after the
        # deployment connector has exchanged it for a server-side reference.
        try:
            connection = app.state.services.broker_connections.connect(payload.broker, dict(payload.credentials))
            return _json(asdict(connection))
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        except RuntimeError as error:
            raise HTTPException(status_code=503, detail=str(error)) from error

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
