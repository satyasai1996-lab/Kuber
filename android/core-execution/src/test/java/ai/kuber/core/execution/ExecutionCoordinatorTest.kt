package ai.kuber.core.execution

import ai.kuber.core.market.intelligence.MarketIntelligenceBuilder
import ai.kuber.core.model.analysis.RiskDecision
import ai.kuber.core.model.broker.BrokerName
import ai.kuber.core.model.broker.OrderRequest
import ai.kuber.core.model.broker.OrderSide
import ai.kuber.core.model.broker.OrderType
import ai.kuber.core.model.broker.TradingMode
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.Quote
import ai.kuber.core.paper.PaperBroker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCoordinatorTest {
    @Test fun `paper lifecycle writes audit and blocks duplicate key`() {
        val market = market(); val paper = PaperBroker().also { it.latestQuote = market.quote }
        val risk = approvedRisk(market); val request = request(market, "paper-key-1")
        val coordinator = ExecutionCoordinator()
        val receipt = coordinator.submitPaper(paper, request, market, risk)
        assertEquals("FILLED", receipt.status)
        assertEquals(1, coordinator.auditEvents().size)
        try { coordinator.submitPaper(paper, request, market, risk); throw AssertionError("expected duplicate block") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `version mismatch fails before broker placement`() {
        val market = market(); val paper = PaperBroker().also { it.latestQuote = market.quote }
        val bad = request(market, "paper-key-2").copy(snapshotId = "old")
        try { ExecutionCoordinator().submitPaper(paper, bad, market, approvedRisk(market)); throw AssertionError("expected version block") } catch (_: IllegalArgumentException) { }
        assertTrue(paper.snapshot().orders.isEmpty())
    }

    private fun market() = MarketIntelligenceBuilder(clock = { 1_000_000L }, idGenerator = ids()).build(
        Quote("NIFTY", 22_000.0, 1_000_000L, "fixture", vwap = 21_950.0),
        listOf(
            OptionContract("NIFTY", 21_900.0, "2026-08-27", OptionType.CE, 100_000, .14, .015, 160.0, 25, 1_000_000L, "fixture"),
            OptionContract("NIFTY", 22_000.0, "2026-08-27", OptionType.CE, 150_000, .14, .020, 105.0, 25, 1_000_000L, "fixture"),
            OptionContract("NIFTY", 22_000.0, "2026-08-27", OptionType.PE, 80_000, .15, .020, 110.0, 25, 1_000_000L, "fixture"),
        ),
    )
    private fun approvedRisk(market: ai.kuber.core.model.market.MarketIntelligence) = RiskDecision(true, 2_000.0, 10, listOf("approved"), market.snapshotId, market.inputVersion)
    private fun request(market: ai.kuber.core.model.market.MarketIntelligence, key: String) = OrderRequest(BrokerName.PAPER, TradingMode.PAPER, "NSE", "NIFTY", OrderSide.BUY, 1, OrderType.MARKET, "MIS", idempotencyKey = key, snapshotId = market.snapshotId, inputVersion = market.inputVersion)
    private fun ids(): () -> String { var n = 0; return { n += 1; "id$n" } }
}
