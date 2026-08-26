"""Small, safe local smoke test for Kuber's analysis flow."""
from __future__ import annotations

import argparse
from dataclasses import asdict

from kuber.agents.base import AgentContext
from kuber.agents.coordinator import AnalysisCoordinator
from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a Kuber paper-analysis smoke test")
    parser.add_argument("--symbol", default="NIFTY")
    args = parser.parse_args()
    normalizer = MarketDataNormalizer()
    quote = normalizer.normalize_quote(args.symbol, {"last_price": 22_000, "vwap": 21_950}, "demo")
    options = normalizer.normalize_options(args.symbol, [
        {"strike": 21900, "expiry": "2026-08-27", "option_type": "CE", "open_interest": 120, "implied_volatility": 14, "gamma": 0.02, "last_price": 100, "lot_size": 25},
        {"strike": 22100, "expiry": "2026-08-27", "option_type": "PE", "open_interest": 80, "implied_volatility": 15, "gamma": 0.02, "last_price": 110, "lot_size": 25},
    ], "demo")
    intelligence = SharedMarketIntelligence().publish(quote, options)
    result = AnalysisCoordinator().analyze(AgentContext(
        intelligence, fundamentals={"quality_score": 40}, news_bias=20, sentiment_score=20, sector_score=20,
    ))
    print(f"analysis={result.analysis_id}; decision={result.final_bias.value}; risk_approved={result.risk.approved}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
