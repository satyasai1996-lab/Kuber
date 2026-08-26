"""Build a validated option chain from a connected provider's instruments and quotes."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timezone
from math import erf, exp, log, pi, sqrt
from typing import Any, Callable, Iterable, Protocol

from kuber.models import OptionContract


class InstrumentQuoteClient(Protocol):
    def instruments(self, exchange: str | None = None) -> list[dict[str, Any]]: ...
    def quote(self, instruments: list[str]) -> dict[str, dict[str, Any]]: ...


def _normal_cdf(value: float) -> float:
    return (1.0 + erf(value / sqrt(2.0))) / 2.0


def _normal_pdf(value: float) -> float:
    return exp(-0.5 * value * value) / sqrt(2.0 * pi)


def _bs_price(spot: float, strike: float, years: float, volatility: float, option_type: str, rate: float) -> float:
    sigma_root_t = volatility * sqrt(years)
    d1 = (log(spot / strike) + (rate + 0.5 * volatility * volatility) * years) / sigma_root_t
    d2 = d1 - sigma_root_t
    discounted_strike = strike * exp(-rate * years)
    if option_type == "CE":
        return spot * _normal_cdf(d1) - discounted_strike * _normal_cdf(d2)
    return discounted_strike * _normal_cdf(-d2) - spot * _normal_cdf(-d1)


def implied_volatility(spot: float, strike: float, years: float, price: float, option_type: str, rate: float = 0.065) -> float:
    """Solve Black-Scholes IV by bisection; return zero for unusable prices."""
    if min(spot, strike, years, price) <= 0:
        return 0.0
    intrinsic = max(spot - strike, 0.0) if option_type == "CE" else max(strike * exp(-rate * years) - spot, 0.0)
    if price <= intrinsic:
        return 0.0
    low, high = 0.0001, 5.0
    if _bs_price(spot, strike, years, high, option_type, rate) < price:
        return 0.0
    for _ in range(64):
        middle = (low + high) / 2
        if _bs_price(spot, strike, years, middle, option_type, rate) < price:
            low = middle
        else:
            high = middle
    return round((low + high) / 2, 6)


def black_scholes_gamma(spot: float, strike: float, years: float, volatility: float, rate: float = 0.065) -> float:
    if min(spot, strike, years, volatility) <= 0:
        return 0.0
    sigma_root_t = volatility * sqrt(years)
    d1 = (log(spot / strike) + (rate + 0.5 * volatility * volatility) * years) / sigma_root_t
    return _normal_pdf(d1) / (spot * sigma_root_t)


def _as_date(value: Any) -> date | None:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    if isinstance(value, str) and value:
        return date.fromisoformat(value[:10])
    return None


@dataclass(frozen=True)
class OptionChainConfig:
    exchange: str = "NFO"
    strike_range_percent: float = 10.0
    max_contracts: int = 200
    risk_free_rate: float = 0.065


class ZerodhaOptionChainBuilder:
    """Use Kite instrument metadata plus live quote fields to build GEX inputs.

    Kite supplies the tradable instrument master, LTP, OI and volume. It does
    not provide a canonical option-chain/Greek payload, so Kuber computes IV
    and gamma once on the backend, then validates and shares that snapshot.
    """

    def __init__(
        self,
        client: InstrumentQuoteClient,
        config: OptionChainConfig | None = None,
        today: Callable[[], date] | None = None,
    ) -> None:
        self.client = client
        self.config = config or OptionChainConfig()
        self.today = today or (lambda: datetime.now(timezone.utc).date())

    def build(self, symbol: str, spot: float) -> tuple[OptionContract, ...]:
        if spot <= 0:
            raise ValueError("spot must be positive for option-chain construction")
        underlying = symbol.upper().strip()
        instruments = [
            item for item in self.client.instruments(self.config.exchange)
            if str(item.get("name", "")).upper() == underlying
            and str(item.get("instrument_type", "")).upper() in {"CE", "PE"}
            and _as_date(item.get("expiry")) is not None
        ]
        future_expiries = sorted({expiry for item in instruments if (expiry := _as_date(item.get("expiry"))) >= self.today()})
        if not future_expiries:
            raise LookupError(f"no active {underlying} option instruments are available from Zerodha")
        expiry = future_expiries[0]
        lower = spot * (1 - self.config.strike_range_percent / 100)
        upper = spot * (1 + self.config.strike_range_percent / 100)
        selected = [
            item for item in instruments
            if _as_date(item.get("expiry")) == expiry and lower <= float(item.get("strike", 0)) <= upper
        ]
        selected.sort(key=lambda item: (abs(float(item["strike"]) - spot), str(item["instrument_type"])))
        selected = selected[: self.config.max_contracts]
        if not selected:
            raise LookupError(f"no {underlying} options are within the configured strike range")
        quote_keys = [f"{self.config.exchange}:{item['tradingsymbol']}" for item in selected]
        quotes = self.client.quote(quote_keys)
        years = max((expiry - self.today()).days + 1, 1) / 365.0
        contracts: list[OptionContract] = []
        for item, quote_key in zip(selected, quote_keys):
            quote = quotes.get(quote_key, {})
            last_price = float(quote.get("last_price", 0.0) or 0.0)
            if last_price <= 0:
                continue
            option_type = str(item["instrument_type"]).upper()
            strike = float(item["strike"])
            iv = implied_volatility(spot, strike, years, last_price, option_type, self.config.risk_free_rate)
            gamma = black_scholes_gamma(spot, strike, years, iv, self.config.risk_free_rate)
            contracts.append(OptionContract(
                underlying=underlying,
                strike=strike,
                expiry=expiry.isoformat(),
                option_type=option_type,
                open_interest=int(quote.get("oi", 0) or 0),
                implied_volatility=round(iv * 100, 4),
                gamma=gamma,
                last_price=last_price,
                lot_size=int(item.get("lot_size", 1)),
                volume=int(quote.get("volume", 0) or 0),
            ))
        if not contracts:
            raise LookupError(f"Zerodha returned no tradable {underlying} option quotes")
        return tuple(sorted(contracts, key=lambda contract: (contract.strike, contract.option_type)))
