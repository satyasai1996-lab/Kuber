"""Real Zerodha Kite OAuth boundary for the Kuber backend.

The Android app opens a Kite login page but submits only its one-time request
token to this backend. The Kite API secret and resulting access token stay in
the backend process. Live orders remain disabled unless the deployment enables
them explicitly.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Protocol

from kuber.brokers.connection import BrokerConnection
from kuber.brokers.providers import ZerodhaBroker
from kuber.market.option_chain import ZerodhaOptionChainBuilder
from kuber.models import OptionContract, OrderRequest, OrderResponse, Quote, utc_now


class KiteClient(Protocol):
    def login_url(self) -> str: ...
    def generate_session(self, request_token: str, api_secret: str) -> dict[str, Any]: ...
    def set_access_token(self, access_token: str) -> None: ...
    def quote(self, instruments: list[str]) -> dict[str, dict[str, Any]]: ...
    def instruments(self, exchange: str | None = None) -> list[dict[str, Any]]: ...
    def positions(self) -> dict[str, list[dict[str, Any]]]: ...
    def holdings(self) -> list[dict[str, Any]]: ...
    def margins(self) -> dict[str, Any]: ...
    def place_order(self, **kwargs: Any) -> str: ...


KiteClientFactory = Callable[[str], KiteClient]


def _default_client_factory(api_key: str) -> KiteClient:
    try:
        from kiteconnect import KiteConnect
    except ImportError as error:  # pragma: no cover - covered through injected clients
        raise RuntimeError("install Kuber with the 'brokers' extra to enable Zerodha") from error
    return KiteConnect(api_key=api_key)


def _instrument_for(symbol: str) -> str:
    """Map the dashboard aliases to Kite instrument names used for quotes."""
    aliases = {"NIFTY": "NSE:NIFTY 50", "BANKNIFTY": "NSE:NIFTY BANK"}
    upper = symbol.upper().strip()
    return aliases.get(upper, upper if ":" in upper else f"NSE:{upper}")


@dataclass
class ZerodhaKiteGateway:
    """Normalises selected Kite operations to Kuber's broker gateway contract."""

    client: KiteClient
    option_chain_builder: ZerodhaOptionChainBuilder | None = None

    def quote(self, symbol: str) -> Quote:
        instrument = _instrument_for(symbol)
        payload = self.client.quote([instrument]).get(instrument)
        if not payload:
            raise LookupError(f"Zerodha returned no quote for {symbol}")
        timestamp = payload.get("timestamp") or payload.get("last_trade_time") or utc_now()
        if isinstance(timestamp, str):
            timestamp = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        if timestamp.tzinfo is None:
            timestamp = timestamp.replace(tzinfo=timezone.utc)
        return Quote(
            symbol=symbol.upper(),
            last_price=float(payload["last_price"]),
            timestamp=timestamp,
            source="zerodha",
            volume=int(payload["volume"]) if payload.get("volume") is not None else None,
        )

    def option_chain(self, symbol: str) -> tuple[OptionContract, ...]:
        builder = self.option_chain_builder or ZerodhaOptionChainBuilder(self.client)
        return builder.build(symbol, self.quote(symbol).last_price)

    def positions(self) -> tuple[dict[str, object], ...]:
        return tuple(self.client.positions().get("net", ()))

    def holdings(self) -> tuple[dict[str, object], ...]:
        return tuple(self.client.holdings())

    def funds(self) -> float:
        equity = self.client.margins().get("equity", {})
        available = equity.get("available", {}) if isinstance(equity, dict) else {}
        return float(available.get("cash", available.get("live_balance", 0.0)))

    def place(self, request: OrderRequest) -> OrderResponse:
        symbol = request.symbol.upper()
        if symbol in {"NIFTY", "BANKNIFTY"}:
            raise ValueError("select an executable equity or option instrument; an index cannot be ordered directly")
        kwargs: dict[str, Any] = {
            "variety": "regular",
            "exchange": "NSE",
            "tradingsymbol": symbol,
            "transaction_type": request.side.value,
            "quantity": request.quantity,
            "product": "MIS",
            "order_type": request.order_type.upper(),
            "tag": f"kuber-{request.idempotency_key[:16]}",
        }
        if request.price is not None:
            kwargs["price"] = request.price
        order_id = self.client.place_order(**kwargs)
        return OrderResponse(str(order_id), "ACCEPTED", "zerodha", request.mode, request.idempotency_key)


@dataclass
class ZerodhaOAuthConnector:
    """Creates a backend-only Kite session from a one-time OAuth request token."""

    api_key: str
    api_secret: str
    live_enabled: bool = False
    client_factory: KiteClientFactory = _default_client_factory
    _client: KiteClient | None = field(default=None, init=False, repr=False)
    _broker: ZerodhaBroker | None = field(default=None, init=False, repr=False)
    _user_id: str | None = field(default=None, init=False, repr=False)

    def _new_client(self) -> KiteClient:
        return self.client_factory(self.api_key)

    def login_url(self) -> str:
        return self._new_client().login_url()

    def connect(self, credentials: dict[str, str]) -> BrokerConnection:
        request_token = credentials.get("request_token", "").strip()
        if not request_token:
            raise ValueError("Zerodha requires the one-time request_token from its Kite login callback")
        client = self._new_client()
        try:
            session = client.generate_session(request_token, api_secret=self.api_secret)
            access_token = str(session["access_token"])
            user_id = str(session.get("user_id", "connected"))
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError("Zerodha returned an invalid authentication response") from error
        client.set_access_token(access_token)
        self._client = client
        self._user_id = user_id
        self._broker = ZerodhaBroker(ZerodhaKiteGateway(client), live_enabled=self.live_enabled)
        return BrokerConnection("zerodha", f"kite:{user_id}", "connected")

    def connected_broker(self) -> ZerodhaBroker:
        if self._broker is None:
            raise RuntimeError("Zerodha has not completed OAuth on this backend")
        return self._broker

    def instrument_master(self) -> tuple[dict[str, Any], ...]:
        """Return the current provider master only after backend OAuth completes."""
        if self._client is None:
            raise RuntimeError("Zerodha has not completed OAuth on this backend")
        return tuple(self._client.instruments())
