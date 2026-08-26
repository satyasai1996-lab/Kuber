from datetime import date
from math import exp
import unittest

from kuber.market.option_chain import (
    OptionChainConfig,
    ZerodhaOptionChainBuilder,
    _bs_price,
    black_scholes_gamma,
    implied_volatility,
)


class FakeKiteMarketClient:
    def instruments(self, exchange=None):
        assert exchange == "NFO"
        return [
            {"name": "NIFTY", "instrument_type": "CE", "expiry": "2026-09-03", "strike": 22000, "lot_size": 25, "tradingsymbol": "NIFTY26SEP22000CE"},
            {"name": "NIFTY", "instrument_type": "PE", "expiry": "2026-09-03", "strike": 22000, "lot_size": 25, "tradingsymbol": "NIFTY26SEP22000PE"},
            {"name": "NIFTY", "instrument_type": "CE", "expiry": "2026-09-10", "strike": 22000, "lot_size": 25, "tradingsymbol": "NIFTY26OCT22000CE"},
            {"name": "NIFTY", "instrument_type": "CE", "expiry": "2026-09-03", "strike": 26000, "lot_size": 25, "tradingsymbol": "NIFTY26SEP26000CE"},
            {"name": "NIFTY", "instrument_type": "PE", "expiry": "2026-08-20", "strike": 22000, "lot_size": 25, "tradingsymbol": "EXPIRED"},
        ]

    def quote(self, instruments):
        return {
            "NFO:NIFTY26SEP22000CE": {"last_price": 340, "oi": 180_000, "volume": 40_000},
            "NFO:NIFTY26SEP22000PE": {"last_price": 300, "oi": 160_000, "volume": 38_000},
        }


class ZerodhaOptionChainBuilderTests(unittest.TestCase):
    def test_builds_nearest_expiry_normalized_chain_and_derives_greeks(self) -> None:
        builder = ZerodhaOptionChainBuilder(
            FakeKiteMarketClient(),
            OptionChainConfig(strike_range_percent=5),
            today=lambda: date(2026, 8, 26),
        )
        chain = builder.build("NIFTY", 22_000)
        self.assertEqual(len(chain), 2)
        self.assertEqual({contract.option_type for contract in chain}, {"CE", "PE"})
        self.assertEqual({contract.expiry for contract in chain}, {"2026-09-03"})
        self.assertTrue(all(contract.open_interest > 0 for contract in chain))
        self.assertTrue(all(0 < contract.implied_volatility < 1 for contract in chain))
        self.assertTrue(all(contract.gamma > 0 for contract in chain))

    def test_black_scholes_golden_prices_iv_and_gamma(self) -> None:
        spot, strike, years, rate, volatility = 100.0, 100.0, 0.5, 0.065, 0.20
        call_price = _bs_price(spot, strike, years, volatility, "CE", rate)
        put_price = _bs_price(spot, strike, years, volatility, "PE", rate)

        self.assertAlmostEqual(call_price, 7.291520438956269, places=12)
        self.assertAlmostEqual(put_price, 4.093765422086875, places=12)
        self.assertAlmostEqual(
            black_scholes_gamma(spot, strike, years, volatility, rate),
            0.026963977606737702,
            places=14,
        )
        self.assertEqual(implied_volatility(spot, strike, years, call_price, "CE", rate), 0.20)
        self.assertEqual(implied_volatility(spot, strike, years, put_price, "PE", rate), 0.20)

    def test_implied_volatility_rejects_arbitrage_bound_violations(self) -> None:
        spot, strike, years, rate = 100.0, 100.0, 0.5, 0.065
        discounted_strike = strike * exp(-rate * years)
        call_lower_bound = max(spot - discounted_strike, 0.0)

        self.assertEqual(implied_volatility(spot, strike, years, call_lower_bound, "CE", rate), 0.0)
        self.assertEqual(implied_volatility(spot, strike, years, call_lower_bound - 0.01, "CE", rate), 0.0)
        self.assertEqual(implied_volatility(spot, strike, years, spot + 0.01, "CE", rate), 0.0)
        self.assertEqual(implied_volatility(spot, strike, years, discounted_strike + 0.01, "PE", rate), 0.0)
        self.assertEqual(implied_volatility(spot, strike, years, 5.0, "INVALID", rate), 0.0)
