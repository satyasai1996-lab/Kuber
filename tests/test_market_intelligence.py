from datetime import timedelta
import unittest

from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer
from kuber.models import OptionContract, utc_now


class MarketIntelligenceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.normalizer = MarketDataNormalizer()

    def test_normalizes_and_publishes_one_shared_gex_snapshot(self) -> None:
        quote = self.normalizer.normalize_quote("NIFTY", {"ltp": 22000, "vwap": 21950}, "fyers")
        options = self.normalizer.normalize_options("NIFTY", [
            {"strike": 21900, "expiry": "2026-08-27", "type": "CE", "oi": 100, "iv": 14, "gamma": 0.02, "ltp": 101, "lot_size": 25},
            {"strike": 22100, "expiry": "2026-08-27", "type": "PE", "oi": 100, "implied_volatility_decimal": 0.15, "gamma": 0.02, "ltp": 100, "lot_size": 25},
        ], "fyers")
        shared = SharedMarketIntelligence()
        intelligence = shared.publish(quote, options)

        self.assertEqual(shared.get("nifty"), intelligence)
        self.assertEqual(intelligence.snapshot.source, "fyers")
        self.assertEqual([contract.implied_volatility for contract in options], [0.14, 0.15])
        self.assertEqual(len(intelligence.snapshot.gex_by_strike), 2)
        self.assertGreater(intelligence.snapshot.gex_by_strike[0].net_gex, 0)
        self.assertLess(intelligence.snapshot.gex_by_strike[1].net_gex, 0)
        self.assertIsNotNone(intelligence.snapshot.gamma_flip)

    def test_rejects_empty_option_chain(self) -> None:
        with self.assertRaises(ValueError):
            self.normalizer.normalize_options("NIFTY", [], "fyers")

    def test_rejects_invalid_or_ambiguous_internal_iv(self) -> None:
        with self.assertRaisesRegex(ValueError, "decimal"):
            self.normalizer.normalize_options("NIFTY", [{
                "strike": 22000,
                "expiry": "2026-08-27",
                "type": "CE",
                "oi": 100,
                "implied_volatility_decimal": 14,
                "gamma": 0.02,
                "ltp": 100,
                "lot_size": 25,
            }], "fixture")

        with self.assertRaisesRegex(ValueError, "decimal"):
            OptionContract(
                underlying="NIFTY",
                strike=22_000,
                expiry="2026-08-27",
                option_type="CE",
                open_interest=100,
                implied_volatility=14,
                gamma=0.02,
                last_price=100,
                lot_size=25,
            )
