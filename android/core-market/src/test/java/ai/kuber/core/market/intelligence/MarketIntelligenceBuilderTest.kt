package ai.kuber.core.market.intelligence

import ai.kuber.core.market.FIXED_NOW
import ai.kuber.core.market.gex.GexCalculator
import ai.kuber.core.market.option
import ai.kuber.core.market.quote
import ai.kuber.core.model.market.DataFreshness
import ai.kuber.core.model.market.GexSnapshot
import ai.kuber.core.model.market.MarketIntelligence
import ai.kuber.core.model.market.OptionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class MarketIntelligenceBuilderTest {
    @Test
    fun `calculates once and publishes one shared immutable version`() {
        val calculator = CountingGexCalculator()
        val ids = ArrayDeque(listOf("input-v1", "snapshot-1"))
        val builder = MarketIntelligenceBuilder(
            gexCalculator = calculator,
            clock = { FIXED_NOW },
            idGenerator = { ids.removeFirst() },
            maxAgeMillis = 30_000,
        )

        val intelligence = builder.build(quote(), listOf(option()))

        assertEquals(1, calculator.calls)
        assertEquals("input-v1", intelligence.inputVersion)
        assertEquals("snapshot-1", intelligence.snapshotId)
        assertEquals(intelligence.inputVersion, intelligence.gexSnapshot.inputVersion)
        assertEquals(intelligence.snapshotId, intelligence.gexSnapshot.snapshotId)
        assertEquals(intelligence.capturedAt, intelligence.gexSnapshot.capturedAt)
        assertEquals(DataFreshness.FRESH, intelligence.freshness)
    }

    @Test
    fun `fresh immutable intelligence becomes stale as time advances`() {
        val ids = ArrayDeque(listOf("input-v1", "snapshot-1"))
        val intelligence = MarketIntelligenceBuilder(
            clock = { FIXED_NOW },
            idGenerator = { ids.removeFirst() },
            maxAgeMillis = 30_000,
        ).build(quote(capturedAt = FIXED_NOW - 1_000), listOf(option(capturedAt = FIXED_NOW - 1_000)))

        assertFalse(intelligence.isStale(FIXED_NOW, 30_000))
        assertTrue(intelligence.isStale(FIXED_NOW + 30_001, 30_000))
        assertTrue(intelligence.gexSnapshot.isStale(FIXED_NOW + 30_001, 30_000))
    }

    private class CountingGexCalculator : GexCalculator() {
        var calls: Int = 0
            private set

        override fun calculate(
            snapshotId: String,
            inputVersion: String,
            capturedAt: Long,
            source: String,
            freshness: DataFreshness,
            symbol: String,
            spot: Double,
            contracts: List<OptionContract>,
        ): GexSnapshot {
            calls += 1
            return super.calculate(
                snapshotId,
                inputVersion,
                capturedAt,
                source,
                freshness,
                symbol,
                spot,
                contracts,
            )
        }
    }
}
