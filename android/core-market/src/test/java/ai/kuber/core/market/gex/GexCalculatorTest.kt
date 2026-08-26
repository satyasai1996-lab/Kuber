package ai.kuber.core.market.gex

import ai.kuber.core.market.option
import ai.kuber.core.model.market.DataFreshness
import ai.kuber.core.model.market.GexRegime
import ai.kuber.core.model.market.GexStrike
import ai.kuber.core.model.market.OptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GexCalculatorTest {
    private val calculator = GexCalculator()

    @Test
    fun `uses the exact upstream dealer exposure formula and sign`() {
        val call = calculator.exposure(100, 0.02, 22_000.0, 25, OptionType.CE)
        val put = calculator.exposure(100, 0.02, 22_000.0, 25, OptionType.PE)

        assertEquals(110_000_000.0, call, 0.0)
        assertEquals(-110_000_000.0, put, 0.0)
    }

    @Test
    fun `interpolates only the first positive to nonpositive flip`() {
        val flip = calculator.findPositiveToNonPositiveFlip(
            listOf(
                GexStrike(100.0, 100.0, 0.0, 100.0),
                GexStrike(200.0, 0.0, -300.0, -300.0),
            ),
        )

        assertEquals(125.0, flip!!, 0.0)
        assertNull(
            calculator.findPositiveToNonPositiveFlip(
                listOf(
                    GexStrike(100.0, 0.0, -100.0, -100.0),
                    GexStrike(200.0, 300.0, 0.0, 300.0),
                ),
            ),
        )
    }

    @Test
    fun `aggregates expiries and identifies walls regime and flip`() {
        val contracts = listOf(
            option(strike = 100.0, expiry = "2099-12-25", optionType = OptionType.CE, openInterest = 10, gamma = 0.01, lotSize = 1),
            option(strike = 110.0, expiry = "2099-12-25", optionType = OptionType.PE, openInterest = 5, gamma = 0.01, lotSize = 1),
            option(strike = 100.0, expiry = "2099-12-31", optionType = OptionType.CE, openInterest = 5, gamma = 0.01, lotSize = 1),
            option(strike = 110.0, expiry = "2099-12-31", optionType = OptionType.PE, openInterest = 20, gamma = 0.01, lotSize = 1),
        )

        val snapshot = calculator.calculate(
            snapshotId = "snapshot",
            inputVersion = "version",
            capturedAt = 1,
            source = "fixture",
            freshness = DataFreshness.FRESH,
            symbol = "NIFTY",
            spot = 100.0,
            contracts = contracts,
        )

        assertEquals(listOf("2099-12-25", "2099-12-31"), snapshot.expirySet)
        assertEquals(500.0, snapshot.gexByExpiry[0].netGex, 0.0)
        assertEquals(-1_500.0, snapshot.gexByExpiry[1].netGex, 0.0)
        assertEquals(-1_000.0, snapshot.totalGex, 0.0)
        assertEquals(103.75, snapshot.gammaFlip!!, 0.0)
        assertEquals(100.0, snapshot.callWall!!, 0.0)
        assertEquals(110.0, snapshot.putWall!!, 0.0)
        assertEquals(listOf(110.0, 100.0), snapshot.gammaWalls)
        assertEquals(GexRegime.NEGATIVE, snapshot.regime)
    }
}
