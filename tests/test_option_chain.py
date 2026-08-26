from datetime import date
import unittest

from kuber.market.option_chain import OptionChainConfig, ZerodhaOptionChainBuilder


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
        self.assertTrue(all(contract.implied_volatility > 0 for contract in chain))
        self.assertTrue(all(contract.gamma > 0 for contract in chain))
