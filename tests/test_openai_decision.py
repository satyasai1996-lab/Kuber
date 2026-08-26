import json
import unittest

from kuber.agents.base import AgentContext
from kuber.agents.coordinator import AnalysisCoordinator
from kuber.ai.openai_decision import OpenAIDecisionService
from kuber.market.intelligence import SharedMarketIntelligence
from kuber.market.normalizer import MarketDataNormalizer
from kuber.models import Bias


def analysis_result():
    normalizer = MarketDataNormalizer()
    quote = normalizer.normalize_quote("NIFTY", {"ltp": 22000, "vwap": 21900}, "test")
    options = normalizer.normalize_options("NIFTY", [
        {"strike": 21900, "expiry": "2026-08-27", "type": "CE", "oi": 150, "iv": 14, "gamma": 0.02, "ltp": 100, "lot_size": 25},
        {"strike": 22100, "expiry": "2026-08-27", "type": "PE", "oi": 70, "iv": 15, "gamma": 0.02, "ltp": 100, "lot_size": 25},
    ], "test")
    intelligence = SharedMarketIntelligence().publish(quote, options)
    return AnalysisCoordinator().analyze(AgentContext(intelligence, fundamentals={"quality_score": 20}))


class OpenAIDecisionTests(unittest.TestCase):
    def test_receives_only_validated_context_and_parses_structured_opinion(self) -> None:
        captured = {}

        def transport(request):
            captured["authorization"] = request.headers["Authorization"]
            captured["request"] = json.loads(request.data.decode())
            return json.dumps({"output": [{"content": [{"type": "output_text", "text": json.dumps({
                "bias": "BULLISH", "confidence": 72, "thesis": "Validated GEX is supportive.",
                "risk_flags": ["Demo data source"], "requires_human_review": True,
            })}]}]}).encode()

        opinion = OpenAIDecisionService("test-key", transport=transport).assess(analysis_result())
        self.assertEqual(opinion.bias, Bias.BULLISH)
        self.assertEqual(opinion.confidence, 72)
        self.assertTrue(opinion.requires_human_review)
        self.assertEqual(captured["authorization"], "Bearer test-key")
        self.assertFalse(captured["request"]["store"])
        self.assertIn("seven_agent_results", captured["request"]["input"])

    def test_key_is_required(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "OPENAI_API_KEY"):
            OpenAIDecisionService(None).assess(analysis_result())
