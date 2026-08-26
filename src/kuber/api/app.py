"""Versioned FastAPI contract for the Kuber Android client."""
from __future__ import annotations

import asyncio
import secrets
from dataclasses import asdict
from datetime import datetime, timezone
from enum import Enum
from queue import Empty
from typing import Any
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from kuber.ai.openai_decision import OpenAIDecisionService
from kuber.agents.base import AgentContext
from kuber.agents.coordinator import AnalysisCoordinator
from kuber.alerts.service import AlertStore
from kuber.backtest.engine import BacktestConfig, BotSignalBacktester
from kuber.brokers.mock import MockBroker
from kuber.brokers.connection import BrokerConnectionService
from kuber.brokers.kite_sandbox import KiteSandboxConnector
from kuber.brokers.zerodha import ZerodhaOAuthConnector
from kuber.config import KuberSettings
from kuber.execution.service import BrokerRegistry, ExecutionService
from kuber.instruments import InstrumentCatalog, InstrumentCatalogSynchronizer
from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer
from kuber.market.streaming import MarketStreamBus
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


def _api_envelope(*, data: Any = None, error: dict[str, Any] | None = None) -> dict[str, Any]:
    return _json({
        "schema_version": "1.0",
        "request_id": uuid4().hex,
        "server_time": datetime.now(timezone.utc).isoformat(),
        "data": data,
        "error": error,
    })


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


class MarketRefreshPayload(BaseModel):
    broker: str = "zerodha"
    fundamentals: dict[str, float] = Field(default_factory=dict)
    news_bias: float = 0
    sentiment_score: float = 0
    sector_score: float = 0


class KuberServices:
    def __init__(self, settings: KuberSettings | None = None, openai_decision: OpenAIDecisionService | None = None) -> None:
        configured_settings = settings or KuberSettings.from_environment()
        self.settings = configured_settings
        self.normalizer = MarketDataNormalizer()
        self.intelligence = SharedMarketIntelligence()
        self.coordinator = AnalysisCoordinator()
        self.registry = BrokerRegistry()
        self.execution = ExecutionService(self.registry)
        self.analyses: dict[str, Any] = {}
        self.latest_analysis_by_symbol: dict[str, Any] = {}
        self.alerts = AlertStore()
        self.stream = MarketStreamBus()
        self.instruments = InstrumentCatalog()
        self.instrument_sync = InstrumentCatalogSynchronizer(self.instruments)
        # Sandbox is intentionally not the default path. A real Kite connection
        # is available only when a backend deployment provides its own app key
        # and secret; none of those values exist in Android or this source tree.
        connectors: dict[str, Any] = {"zerodha_sandbox": KiteSandboxConnector()}
        if configured_settings.zerodha_api_key and configured_settings.zerodha_api_secret:
            connectors["zerodha"] = ZerodhaOAuthConnector(
                configured_settings.zerodha_api_key,
                configured_settings.zerodha_api_secret,
                live_enabled=configured_settings.live_orders_enabled,
            )
        self.broker_connections = BrokerConnectionService(connectors)
        self.openai_decision = openai_decision or OpenAIDecisionService(
            configured_settings.openai_api_key, configured_settings.openai_model,
        )

    def prepare(self, request: AnalysisRequest):
        quote = self.normalizer.normalize_quote(request.symbol, request.quote.model_dump(), request.quote.source)
        options = self.normalizer.normalize_options(request.symbol, [item.model_dump() for item in request.options], request.quote.source)
        intelligence = self.intelligence.publish(quote, options)
        mock = MockBroker(quote=quote, option_chain=options)
        self.registry.register(mock)
        self.stream.publish(quote)
        return intelligence

    def refresh_from_broker(self, symbol: str, payload: MarketRefreshPayload):
        """Fetch a fresh provider quote/option chain and publish one analysis snapshot."""
        broker = self.registry.get(payload.broker.lower())
        quote = broker.get_quote(symbol)
        options = broker.get_options_chain(symbol)
        intelligence = self.intelligence.publish(quote, options)
        self.registry.register(MockBroker(quote=quote, option_chain=options))
        self.stream.publish(quote)
        result = self.coordinator.analyze(AgentContext(
            intelligence=intelligence,
            fundamentals=payload.fundamentals,
            news_bias=payload.news_bias,
            sentiment_score=payload.sentiment_score,
            sector_score=payload.sector_score,
        ))
        self.analyses[result.analysis_id] = result
        self.latest_analysis_by_symbol[quote.symbol] = result
        return result

    def start_demo(self):
        """Create a visible, strictly paper-only fixture session.

        It allows a new Android installation to exercise the complete bot →
        risk → trade-plan → paper-order workflow without pretending it has
        broker or live market data.
        """
        request = AnalysisRequest(
            symbol="NIFTY",
            quote=QuotePayload(last_price=22_000, vwap=21_965, volume=1_500_000, source="demo_fixture"),
            options=[
                OptionPayload(strike=21_800, expiry="2026-08-27", option_type="CE", open_interest=100_000, implied_volatility=14.1, gamma=0.009, last_price=235, lot_size=25, volume=42_000),
                OptionPayload(strike=21_900, expiry="2026-08-27", option_type="CE", open_interest=120_000, implied_volatility=13.8, gamma=0.015, last_price=168, lot_size=25, volume=54_000),
                OptionPayload(strike=22_000, expiry="2026-08-27", option_type="CE", open_interest=165_000, implied_volatility=13.5, gamma=0.022, last_price=111, lot_size=25, volume=69_000),
                OptionPayload(strike=22_000, expiry="2026-08-27", option_type="PE", open_interest=142_000, implied_volatility=14.2, gamma=0.022, last_price=105, lot_size=25, volume=65_000),
                OptionPayload(strike=22_100, expiry="2026-08-27", option_type="PE", open_interest=180_000, implied_volatility=14.9, gamma=0.016, last_price=160, lot_size=25, volume=58_000),
                OptionPayload(strike=22_200, expiry="2026-08-27", option_type="PE", open_interest=145_000, implied_volatility=15.6, gamma=0.010, last_price=230, lot_size=25, volume=39_000),
            ],
            fundamentals={"quality_score": 15},
            news_bias=8,
            sentiment_score=10,
            sector_score=6,
        )
        intelligence = self.prepare(request)
        result = self.coordinator.analyze(AgentContext(
            intelligence=intelligence,
            fundamentals=request.fundamentals,
            news_bias=request.news_bias,
            sentiment_score=request.sentiment_score,
            sector_score=request.sector_score,
        ))
        self.analyses[result.analysis_id] = result
        self.latest_analysis_by_symbol["NIFTY"] = result
        return result

    def connect_broker(self, broker: str, credentials: dict[str, str]):
        connection = self.broker_connections.connect(broker, credentials)
        connector = self.broker_connections.connector(connection.broker)
        connected_broker = getattr(connector, "connected_broker", None)
        if callable(connected_broker):
            self.registry.register(connected_broker())
        return connection

    def sync_instrument_catalog(self, broker: str) -> dict[str, Any]:
        normalized = broker.lower().replace(" ", "_")
        connector = self.broker_connections.connector(normalized)
        if normalized == "zerodha":
            return _json(asdict(self.instrument_sync.sync_zerodha(connector)))
        raise RuntimeError(f"{normalized} instrument normalization is not implemented")


def create_app(services: KuberServices | None = None, settings: KuberSettings | None = None) -> FastAPI:
    runtime_settings = settings or KuberSettings.from_environment()
    production = runtime_settings.environment.lower() == "production"
    app = FastAPI(
        title="Kuber API",
        version="v1",
        docs_url=None if production else "/docs",
        openapi_url=None if production else "/openapi.json",
    )
    app.state.settings = runtime_settings
    app.state.services = services or KuberServices(settings=app.state.settings)
    public_host = None
    if app.state.settings.public_base_url:
        from urllib.parse import urlsplit

        public_host = urlsplit(app.state.settings.public_base_url).hostname
    allowed_hosts = {
        "127.0.0.1",
        "localhost",
        "testserver",
        *(app.state.settings.allowed_hosts or ()),
        *((public_host,) if public_host else ()),
    }
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=sorted(allowed_hosts))

    @app.middleware("http")
    async def token_auth(request: Request, call_next):
        # A development install may run without a token. Any configured token protects
        # every data/execution endpoint; Android never receives broker credentials.
        token = app.state.settings.api_token
        if token and request.url.path not in {"/health", "/docs", "/openapi.json"}:
            supplied = request.headers.get("Authorization", "")
            if not secrets.compare_digest(supplied, f"Bearer {token}"):
                if request.url.path.startswith("/api/v1/"):
                    response = JSONResponse(
                        _api_envelope(error={
                            "code": "unauthorized",
                            "message": "A valid Kuber session is required.",
                            "retryable": False,
                            "details": None,
                        }),
                        status_code=401,
                    )
                else:
                    response = JSONResponse({"detail": "unauthorized"}, status_code=401)
            else:
                response = await call_next(request)
        else:
            response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        if request.url.path.startswith(("/api/", "/analysis", "/market", "/options", "/orders", "/portfolio", "/brokers", "/alerts", "/backtest")):
            response.headers["Cache-Control"] = "no-store"
        if request.url.scheme == "https" or request.headers.get("X-Forwarded-Proto", "").lower() == "https":
            response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        return response

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "service": "kuber"}

    @app.get("/api/v1/instruments/search")
    def search_instruments(
        q: str = "",
        exchanges: str | None = None,
        types: str | None = None,
        limit: int = 25,
    ) -> Any:
        """Search the latest provider-backed catalogue without ambiguous symbol selection."""
        exchange_filter = {item.strip().upper() for item in exchanges.split(",") if item.strip()} if exchanges else None
        type_filter = {item.strip().upper() for item in types.split(",") if item.strip()} if types else None
        try:
            result = app.state.services.instruments.search(
                q,
                exchanges=exchange_filter,
                instrument_types=type_filter,
                limit=limit,
            )
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        if not result.ready:
            return JSONResponse(
                _api_envelope(error={
                    "code": "catalog_unavailable",
                    "message": "Instrument catalog is not ready; connect and synchronize a backend market provider.",
                    "retryable": True,
                    "details": None,
                }),
                status_code=503,
            )
        return _api_envelope(data={
            "items": [item.to_public_dict() for item in result.items],
            "next_cursor": None,
            "catalog_version": result.catalog_version,
            "as_of": result.as_of,
        })

    @app.get("/api/v1/instruments/status")
    def instrument_catalog_status() -> Any:
        """Expose catalogue readiness and freshness without returning credentials."""
        return _api_envelope(data=asdict(app.state.services.instruments.status()))

    @app.post("/api/v1/admin/instruments/sync/{broker}")
    def sync_instruments(broker: str) -> Any:
        """Atomically replace the catalogue from an authenticated backend connector."""
        try:
            return _json(app.state.services.sync_instrument_catalog(broker))
        except (RuntimeError, ValueError) as error:
            raise HTTPException(status_code=503, detail=str(error)) from error

    @app.post("/analysis/analyze")
    def analyze(request: AnalysisRequest) -> Any:
        intelligence = app.state.services.prepare(request)
        result = app.state.services.coordinator.analyze(AgentContext(
            intelligence=intelligence, fundamentals=request.fundamentals, news_bias=request.news_bias,
            sentiment_score=request.sentiment_score, sector_score=request.sector_score,
        ))
        app.state.services.analyses[result.analysis_id] = result
        app.state.services.latest_analysis_by_symbol[result.intelligence.quote.symbol] = result
        return _json(asdict(result))

    @app.post("/demo/start")
    def start_demo() -> Any:
        result = app.state.services.start_demo()
        intelligence = result.intelligence
        return _json({
            "mode": "PAPER_DEMO",
            "symbol": intelligence.quote.symbol,
            "source": "demo_fixture",
            "notice": "This is fixture data for paper-trading workflow validation, not live market data.",
            "quote": asdict(intelligence.quote),
            "gex": asdict(intelligence.snapshot),
            "options": [asdict(contract) for contract in intelligence.option_chain],
            "analysis": asdict(result),
        })

    @app.post("/analysis/deep-analyze")
    def deep_analyze(request: AnalysisRequest) -> Any:
        return analyze(request)

    @app.get("/analysis/{analysis_id}")
    def get_analysis(analysis_id: str) -> Any:
        result = app.state.services.analyses.get(analysis_id)
        if result is None:
            raise HTTPException(status_code=404, detail="analysis not found")
        return _json(asdict(result))

    @app.get("/analysis/latest/{symbol}")
    def get_latest_analysis(symbol: str) -> Any:
        result = app.state.services.latest_analysis_by_symbol.get(symbol.upper())
        if result is None:
            raise HTTPException(status_code=404, detail="analysis not found; submit normalized provider data first")
        return _json(asdict(result))

    @app.post("/analysis/openai-opinion/{symbol}")
    def openai_opinion(symbol: str) -> Any:
        """Optional ChatGPT opinion over Kuber's validated snapshot only.

        This never bypasses the deterministic seven-agent scorecard, risk veto,
        or the separate live-order confirmation flow.
        """
        result = app.state.services.latest_analysis_by_symbol.get(symbol.upper())
        if result is None:
            raise HTTPException(status_code=404, detail="analysis not found; submit validated provider data first")
        try:
            return _json(asdict(app.state.services.openai_decision.assess(result)))
        except RuntimeError as error:
            raise HTTPException(status_code=503, detail=str(error)) from error

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

    @app.post("/market/refresh/{symbol}")
    def refresh_market(symbol: str, payload: MarketRefreshPayload) -> Any:
        """Refresh backend market intelligence from an already connected broker.

        This data-only operation neither creates nor submits an order.
        """
        try:
            return _json(asdict(app.state.services.refresh_from_broker(symbol, payload)))
        except (LookupError, NotImplementedError, ValueError) as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @app.websocket("/market/stream/{symbol}")
    async def market_stream(websocket: WebSocket, symbol: str) -> None:
        """Authenticated quote fan-out with reconnect-safe latest-value replay."""
        expected = app.state.settings.api_token
        supplied = websocket.headers.get("Authorization", "")
        if expected and not secrets.compare_digest(supplied, f"Bearer {expected}"):
            await websocket.close(code=1008)
            return
        normalized = symbol.upper()
        await websocket.accept()
        subscriber = app.state.services.stream.subscribe(normalized)
        try:
            await websocket.send_json({"kind": "stream_status", "state": "connected", "symbol": normalized})
            latest = app.state.services.stream.latest(normalized)
            if latest is not None:
                await websocket.send_json(app.state.services.stream.payload(latest))
            while True:
                try:
                    quote = await asyncio.to_thread(subscriber.get, True, 15)
                except Empty:
                    await websocket.send_json({"kind": "heartbeat", "symbol": normalized})
                    continue
                await websocket.send_json(app.state.services.stream.payload(quote))
        except WebSocketDisconnect:
            return
        finally:
            app.state.services.stream.unsubscribe(normalized, subscriber)

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
            connection = app.state.services.connect_broker(payload.broker, dict(payload.credentials))
            return _json(asdict(connection))
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        except RuntimeError as error:
            raise HTTPException(status_code=503, detail=str(error)) from error

    @app.get("/brokers/zerodha/sandbox/login-url")
    def zerodha_sandbox_login_url() -> dict[str, str]:
        return {
            "broker": "zerodha_sandbox",
            "environment": "demo_only",
            "login_url": app.state.services.broker_connections.login_url("zerodha_sandbox"),
        }

    @app.get("/brokers/zerodha/login-url")
    def zerodha_login_url() -> dict[str, str]:
        """Start a real user-owned Kite OAuth flow configured on this backend."""
        try:
            return {
                "broker": "zerodha",
                "environment": "production_oauth",
                "login_url": app.state.services.broker_connections.login_url("zerodha"),
            }
        except RuntimeError as error:
            raise HTTPException(
                status_code=503,
                detail="Zerodha is not configured on this backend. Set KUBER_ZERODHA_API_KEY and KUBER_ZERODHA_API_SECRET outside the APK.",
            ) from error

    @app.get("/brokers/zerodha/callback")
    def zerodha_callback(request_token: str) -> dict[str, str]:
        """Optional registered redirect endpoint for browser-based Kite OAuth."""
        try:
            connection = app.state.services.connect_broker("zerodha", {"request_token": request_token})
            return {
                "status": connection.status,
                "broker": connection.broker,
                "message": "Zerodha is connected. Return to Kuber and refresh Broker status.",
            }
        except (ValueError, RuntimeError) as error:
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
