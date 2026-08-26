package ai.kuber.core.agents

import ai.kuber.core.market.intelligence.MarketIntelligenceBuilder
import ai.kuber.core.market.options.OptionsAnalyticsCalculator
import ai.kuber.core.model.analysis.AnalystName
import ai.kuber.core.model.analysis.Bias
import ai.kuber.core.model.analysis.PipelineStage
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisOrchestratorTest {
    @Test fun `pipeline retains exact ordered stages and shared snapshot identity`() {
        val now = 1_000_000L
        val market = MarketIntelligenceBuilder(clock = { now }, idGenerator = sequenceIds()).build(quote(now), chain(now))
        val result = AnalysisOrchestrator(idGenerator = { "analysis" }).analyze(
            AnalysisContext(market, OptionsAnalyticsCalculator().calculate(market), fundamentalScore = 20.0, newsMacroScore = -15.0, sentimentScore = 18.0, sectorRotationScore = 5.0), now,
        )
        assertEquals(PipelineStage.entries.toList(), result.stages)
        assertEquals(AnalystName.entries.toSet(), result.analysts.map { it.analyst }.toSet())
        assertTrue(result.analysts.all { it.snapshotId == market.snapshotId && it.inputVersion == market.inputVersion })
        assertEquals(3, result.tradePlans.size)
        assertTrue(result.tradePlans.all { it.snapshotId == market.snapshotId })
        assertTrue(result.debate.bullRebuttal.isNotBlank() && result.debate.bearRebuttal.isNotBlank())
    }

    @Test fun `unavailable provider becomes structured unavailable not a fake score`() {
        val now = 1_000_000L
        val market = MarketIntelligenceBuilder(clock = { now }, idGenerator = sequenceIds()).build(quote(now), chain(now))
        val result = AnalysisOrchestrator(idGenerator = { "analysis" }).analyze(AnalysisContext(market, OptionsAnalyticsCalculator().calculate(market)), now)
        assertEquals(Bias.UNAVAILABLE, result.analysts.first { it.analyst == AnalystName.FUNDAMENTAL }.bias)
        assertEquals(Bias.UNAVAILABLE, result.analysts.first { it.analyst == AnalystName.NEWS_MACRO }.bias)
    }

    @Test fun `final risk gate blocks neutral fund manager`() {
        val now = 1_000_000L
        val market = MarketIntelligenceBuilder(clock = { now }, idGenerator = sequenceIds()).build(quote(now), chain(now))
        val result = AnalysisOrchestrator(idGenerator = { "analysis" }).analyze(AnalysisContext(market, OptionsAnalyticsCalculator().calculate(market)), now)
        assertFalse(result.finalRisk.approved)
        assertTrue(result.tradePlans.all { it.quantity == 0 })
    }

    private fun quote(now: Long) = Quote("NIFTY", 22_000.0, now, "fixture", vwap = 21_950.0)
    private fun chain(now: Long) = listOf(
        OptionContract("NIFTY", 21_900.0, "2026-08-27", OptionType.CE, 100_000, .14, .015, 160.0, 25, now, "fixture", openInterestChange = 10),
        OptionContract("NIFTY", 22_000.0, "2026-08-27", OptionType.CE, 150_000, .14, .020, 105.0, 25, now, "fixture", openInterestChange = 12),
        OptionContract("NIFTY", 22_000.0, "2026-08-27", OptionType.PE, 80_000, .15, .020, 110.0, 25, now, "fixture", openInterestChange = -8),
        OptionContract("NIFTY", 22_100.0, "2026-08-27", OptionType.PE, 60_000, .16, .012, 190.0, 25, now, "fixture", openInterestChange = -6),
    )
    private fun sequenceIds(): () -> String { var index = 0; return { index += 1; "id$index" } }
}
