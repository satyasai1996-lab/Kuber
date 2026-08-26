package ai.kuber.core.risk

import ai.kuber.core.market.intelligence.MarketIntelligenceBuilder
import ai.kuber.core.model.analysis.Bias
import ai.kuber.core.model.analysis.FundManagerDecision
import ai.kuber.core.model.analysis.GexAlignment
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.Quote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskGateTest {
    @Test fun `stale snapshot is vetoed`() {
        val now = 1_000_000L
        val market = MarketIntelligenceBuilder(clock = { now }, idGenerator = { "v" }).build(
            quote(now), chain(now),
        )
        val decision = FundManagerDecision(Bias.BULLISH, 70, GexAlignment.SUPPORTS, listOf("fixture"))
        val result = RiskGate(RiskSettings(maxSnapshotAgeMillis = 10)).evaluate(market, decision, 70.0, now + 11)
        assertFalse(result.approved)
    }

    @Test fun `approved decision gets bounded quantity`() {
        val now = 1_000_000L
        val market = MarketIntelligenceBuilder(clock = { now }, idGenerator = { "v" }).build(
            quote(now), chain(now),
        )
        val result = RiskGate().evaluate(
            market, FundManagerDecision(Bias.BULLISH, 70, GexAlignment.SUPPORTS, listOf("fixture")), 70.0, now,
        )
        assertTrue(result.approved)
        assertTrue(result.allowedQuantity > 0)
    }

    private fun quote(now: Long) = Quote("NIFTY", 22_000.0, now, "fixture", vwap = 21_950.0)

    private fun chain(now: Long) = listOf(
        OptionContract("NIFTY", 21_900.0, "2026-08-27", OptionType.CE, 100_000, 0.14, 0.015, 160.0, 25, now, "fixture"),
        OptionContract("NIFTY", 22_000.0, "2026-08-27", OptionType.CE, 150_000, 0.14, 0.02, 105.0, 25, now, "fixture"),
        OptionContract("NIFTY", 22_000.0, "2026-08-27", OptionType.PE, 80_000, 0.15, 0.02, 110.0, 25, now, "fixture"),
        OptionContract("NIFTY", 22_100.0, "2026-08-27", OptionType.PE, 60_000, 0.16, 0.012, 190.0, 25, now, "fixture"),
    )
}
