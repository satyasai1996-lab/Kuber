import unittest

from kuber.agents.base import AgentContext
from kuber.agents.coordinator import AnalysisCoordinator
from kuber.agents.validation import AnalysisValidationBot
from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer
from kuber.models import AgentName, Bias
from kuber.risk.engine import RiskEngine, RiskLimits


def intelligence():
    normalizer = MarketDataNormalizer()
    quote = normalizer.normalize_quote("NIFTY", {"ltp": 22000, "vwap": 21900}, "mock")
    chain = normalizer.normalize_options("NIFTY", [
        {"strike": 21900, "expiry": "2026-08-27", "type": "CE", "oi": 150, "iv": 14, "gamma": 0.02, "ltp": 100, "lot_size": 25},
        {"strike": 22100, "expiry": "2026-08-27", "type": "PE", "oi": 70, "iv": 15, "gamma": 0.02, "ltp": 100, "lot_size": 25},
    ], "mock")
    return SharedMarketIntelligence().publish(quote, chain)


class AnalysisTests(unittest.TestCase):
    def test_seven_agents_share_the_same_validated_snapshot(self) -> None:
        state = intelligence()
        result = AnalysisCoordinator().analyze(AgentContext(state, fundamentals={"quality_score": 20}, news_bias=12, sentiment_score=15, sector_score=10))

        self.assertEqual(len(result.agents), 7)
        self.assertEqual({agent.agent for agent in result.agents}, set(AgentName))
        self.assertEqual({agent.intelligence_version for agent in result.agents}, {state.version})
        options = next(agent for agent in result.agents if agent.agent == AgentName.OPTIONS)
        self.assertEqual(options.metadata["gex_snapshot_id"], state.snapshot.snapshot_id)
        self.assertTrue(result.risk.approved)
        self.assertEqual(result.final_bias, Bias.BULLISH)
        self.assertEqual(len(result.trade_plans), 3)
        validation = AnalysisValidationBot().validate(result)
        self.assertTrue(validation.valid)

    def test_stale_gex_is_a_risk_veto(self) -> None:
        state = intelligence()
        stale_snapshot = state.snapshot.__class__(**{**state.snapshot.__dict__, "timestamp": state.snapshot.timestamp.replace(year=2020)})
        stale_state = state.__class__(snapshot=stale_snapshot, quote=state.quote, option_chain=state.option_chain, version=state.version)
        result = AnalysisCoordinator(risk_engine=RiskEngine(RiskLimits(gex_max_age_seconds=1))).analyze(AgentContext(stale_state, fundamentals={"quality_score": 20}))

        self.assertFalse(result.risk.approved)
        self.assertEqual(result.final_bias, Bias.NEUTRAL)
