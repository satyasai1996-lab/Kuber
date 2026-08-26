package ai.kuber.core.market.options

import ai.kuber.core.market.FIXED_NOW
import ai.kuber.core.market.intelligence.MarketIntelligenceBuilder
import ai.kuber.core.market.option
import ai.kuber.core.market.quote
import ai.kuber.core.model.market.OpenInterestChangeDirection
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.OptionsAnalyticsField
import ai.kuber.core.model.market.OptionsAnomalyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class OptionsAnalyticsCalculatorTest {
    private val calculator = OptionsAnalyticsCalculator()

    @Test
    fun `calculates PCR IV smile key strikes and provider OI change`() {
        val market = market(
            option(strike = 100.0, optionType = OptionType.CE, openInterest = 100, volume = 50, impliedVolatility = 0.20, openInterestChange = 10),
            option(strike = 100.0, optionType = OptionType.PE, openInterest = 200, volume = 100, impliedVolatility = 0.24, openInterestChange = -5),
            option(strike = 110.0, optionType = OptionType.CE, openInterest = 300, volume = 150, impliedVolatility = 0.22),
            option(strike = 110.0, optionType = OptionType.PE, openInterest = 100, volume = 50, impliedVolatility = 0.26, openInterestChange = 0),
        )

        val analytics = calculator.calculate(market)

        assertEquals(0.75, analytics.putCallRatios.openInterest!!, 0.0)
        assertEquals(0.75, analytics.putCallRatios.volume!!, 0.0)
        assertEquals(0.21, analytics.averageCallIv!!, 0.000_000_1)
        assertEquals(0.25, analytics.averagePutIv!!, 0.000_000_1)
        assertEquals(0.04, analytics.putMinusCallIvSkew!!, 0.000_000_1)
        assertEquals(2, analytics.ivSmile.size)
        assertEquals(100.0, analytics.ivSmile[0].strike, 0.0)
        assertEquals(0.04, analytics.ivSmile[0].putMinusCallSkew!!, 0.000_000_1)
        assertEquals(110.0, analytics.maxCallOpenInterestStrike!!, 0.0)
        assertEquals(100.0, analytics.maxPutOpenInterestStrike!!, 0.0)
        assertEquals(3, analytics.openInterestChangeSignals.size)
        assertEquals(OpenInterestChangeDirection.BUILDUP, analytics.openInterestChangeSignals[0].direction)
        assertEquals(OpenInterestChangeDirection.UNWINDING, analytics.openInterestChangeSignals[1].direction)
        assertEquals(OpenInterestChangeDirection.UNCHANGED, analytics.openInterestChangeSignals[2].direction)
        assertEquals(3, analytics.openInterestChangeAvailableContracts)
        assertEquals(4, analytics.openInterestChangeTotalContracts)
        assertFalse(OptionsAnalyticsField.OPEN_INTEREST_CHANGE in analytics.unavailableFields)
    }

    @Test
    fun `reports deterministic PCR skew and large OI change anomalies`() {
        val analytics = OptionsAnalyticsCalculator(
            extremePcrLow = 0.5,
            extremePcrHigh = 1.5,
            materialIvSkew = 0.05,
            largeOpenInterestChangeRatio = 0.25,
        ).calculate(
            market(
                option(strike = 100.0, optionType = OptionType.CE, openInterest = 100, volume = 100, impliedVolatility = 0.15, openInterestChange = 50),
                option(strike = 100.0, optionType = OptionType.PE, openInterest = 200, volume = 200, impliedVolatility = 0.25, openInterestChange = -100),
            ),
        )

        assertEquals(
            listOf(
                OptionsAnomalyKind.EXTREME_OPEN_INTEREST_PCR,
                OptionsAnomalyKind.EXTREME_VOLUME_PCR,
                OptionsAnomalyKind.IV_SKEW,
                OptionsAnomalyKind.LARGE_OPEN_INTEREST_CHANGE,
                OptionsAnomalyKind.LARGE_OPEN_INTEREST_CHANGE,
            ),
            analytics.anomalies.map { it.kind },
        )
        assertEquals(0.5, analytics.anomalies[3].observedValue, 0.0)
        assertEquals(100.0, analytics.anomalies[3].strike!!, 0.0)
    }

    @Test
    fun `marks a missing option side unavailable instead of inventing values`() {
        val analytics = calculator.calculate(
            market(
                option(strike = 100.0, optionType = OptionType.CE, openInterest = 10, volume = 5, impliedVolatility = 0.20),
            ),
        )

        assertNull(analytics.putCallRatios.openInterest)
        assertNull(analytics.putCallRatios.volume)
        assertEquals(0.20, analytics.averageCallIv!!, 0.0)
        assertNull(analytics.averagePutIv)
        assertNull(analytics.putMinusCallIvSkew)
        assertNull(analytics.maxPutOpenInterestStrike)
        assertNull(analytics.ivSmile.single().putIv)
        assertNull(analytics.ivSmile.single().putMinusCallSkew)
        assertTrue(OptionsAnalyticsField.PUT_CALL_OPEN_INTEREST_RATIO in analytics.unavailableFields)
        assertTrue(OptionsAnalyticsField.AVERAGE_PUT_IV in analytics.unavailableFields)
        assertTrue(OptionsAnalyticsField.PUT_MINUS_CALL_IV_SKEW in analytics.unavailableFields)
        assertTrue(OptionsAnalyticsField.OPEN_INTEREST_CHANGE in analytics.unavailableFields)
    }

    @Test
    fun `zero denominators and zero OI expose unavailable ratios and key strikes`() {
        val analytics = calculator.calculate(
            market(
                option(strike = 100.0, optionType = OptionType.CE, openInterest = 0, volume = 0, impliedVolatility = 0.20),
                option(strike = 100.0, optionType = OptionType.PE, openInterest = 0, volume = 10, impliedVolatility = 0.22),
            ),
        )

        assertNull(analytics.putCallRatios.openInterest)
        assertNull(analytics.putCallRatios.volume)
        assertNull(analytics.maxCallOpenInterestStrike)
        assertNull(analytics.maxPutOpenInterestStrike)
        assertEquals(0.02, analytics.putMinusCallIvSkew!!, 0.000_000_1)
        assertTrue(OptionsAnalyticsField.MAX_CALL_OPEN_INTEREST_STRIKE in analytics.unavailableFields)
        assertTrue(OptionsAnalyticsField.MAX_PUT_OPEN_INTEREST_STRIKE in analytics.unavailableFields)
        assertTrue(analytics.anomalies.isEmpty())
    }

    @Test
    fun `keeps authoritative GEX version untouched`() {
        val market = market(
            option(strike = 100.0, optionType = OptionType.CE),
            option(strike = 100.0, optionType = OptionType.PE),
        )
        val originalGex = market.gexSnapshot.copy()

        val analytics = calculator.calculate(market)

        assertEquals(originalGex, market.gexSnapshot)
        assertEquals(market.snapshotId, analytics.gexReference.snapshotId)
        assertEquals(market.inputVersion, analytics.gexReference.inputVersion)
        assertEquals(market.capturedAt, analytics.gexReference.capturedAt)
    }

    private fun market(vararg contracts: OptionContract) =
        MarketIntelligenceBuilder(
            clock = { FIXED_NOW },
            idGenerator = ArrayDeque(listOf("input-v1", "snapshot-1"))::removeFirst,
            maxAgeMillis = 30_000,
        ).build(
            quote = quote(price = 100.0),
            optionChain = contracts.toList(),
        )
}
