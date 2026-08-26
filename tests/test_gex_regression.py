from datetime import datetime, timezone
import unittest

from kuber.market.gex import GexCalculator
from kuber.models import GexStrike, OptionContract


def option(
    option_type: str,
    *,
    strike: float = 22_000.0,
    expiry: str = "2026-09-03",
    open_interest: int = 100,
    gamma: float = 0.02,
    lot_size: int = 25,
) -> OptionContract:
    return OptionContract(
        underlying="NIFTY",
        strike=strike,
        expiry=expiry,
        option_type=option_type,
        open_interest=open_interest,
        implied_volatility=0.20,
        gamma=gamma,
        last_price=100.0,
        lot_size=lot_size,
    )


class GexRegressionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.calculator = GexCalculator()
        self.timestamp = datetime(2026, 8, 26, tzinfo=timezone.utc)

    def snapshot(self, contracts: tuple[OptionContract, ...], spot: float = 22_000.0):
        return self.calculator.build_snapshot("NIFTY", spot, contracts, "fixture", self.timestamp)

    def test_exact_upstream_gex_magnitude_and_sign(self) -> None:
        call = self.snapshot((option("CE"),)).gex_by_strike[0]
        put = self.snapshot((option("PE"),)).gex_by_strike[0]

        self.assertEqual(call.call_gex, 110_000_000.0)
        self.assertEqual(call.net_gex, 110_000_000.0)
        self.assertEqual(put.put_gex, -110_000_000.0)
        self.assertEqual(put.net_gex, -110_000_000.0)

    def test_exact_upstream_positive_to_nonpositive_flip_truth_table(self) -> None:
        cases = (
            ("positive_to_negative", (GexStrike(100, 100, 0, 100), GexStrike(200, 0, -300, -300)), 125.0),
            ("negative_to_positive", (GexStrike(100, 0, -100, -100), GexStrike(200, 300, 0, 300)), None),
            ("positive_to_zero", (GexStrike(100, 100, 0, 100), GexStrike(200, 0, 0, 0)), 200.0),
            ("zero_to_negative", (GexStrike(100, 0, 0, 0), GexStrike(200, 0, -100, -100)), None),
        )

        for label, strikes, expected in cases:
            with self.subTest(label=label):
                self.assertEqual(self.calculator._find_flip(strikes), expected)

    def test_first_positive_to_nonpositive_crossing_wins(self) -> None:
        strikes = (
            GexStrike(100, 100, 0, 100),
            GexStrike(150, 0, -100, -100),
            GexStrike(200, 100, 0, 100),
            GexStrike(250, 0, -100, -100),
        )
        self.assertEqual(self.calculator._find_flip(strikes), 125.0)

    def test_multi_expiry_fixture_aggregates_to_exact_flip(self) -> None:
        contracts = (
            option("CE", strike=100, expiry="2026-09-03", open_interest=10, gamma=.01, lot_size=1),
            option("PE", strike=110, expiry="2026-09-03", open_interest=5, gamma=.01, lot_size=1),
            option("CE", strike=100, expiry="2026-09-10", open_interest=5, gamma=.01, lot_size=1),
            option("PE", strike=110, expiry="2026-09-10", open_interest=20, gamma=.01, lot_size=1),
        )
        snapshot = self.snapshot(contracts, spot=100)

        self.assertEqual(snapshot.expiry_set, ("2026-09-03", "2026-09-10"))
        self.assertEqual(snapshot.gex_by_strike[0].net_gex, 1_500.0)
        self.assertEqual(snapshot.gex_by_strike[1].net_gex, -2_500.0)
        self.assertEqual(snapshot.total_gex, -1_000.0)
        self.assertEqual(snapshot.gamma_flip, 103.75)


if __name__ == "__main__":
    unittest.main()
