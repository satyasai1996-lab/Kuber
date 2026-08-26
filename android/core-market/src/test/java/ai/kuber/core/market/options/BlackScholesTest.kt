package ai.kuber.core.market.options

import ai.kuber.core.model.market.OptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackScholesTest {
    @Test
    fun `recovers input IV and produces a sane gamma`() {
        val expectedVolatility = 0.20
        val callPrice = BlackScholes.optionPrice(
            optionType = OptionType.CE,
            spot = 100.0,
            strike = 100.0,
            timeToExpiryYears = 0.5,
            riskFreeRate = 0.05,
            volatility = expectedVolatility,
        )

        val greeks = BlackScholes.calculateGreeks(
            optionType = OptionType.CE,
            marketPrice = callPrice,
            spot = 100.0,
            strike = 100.0,
            timeToExpiryYears = 0.5,
            riskFreeRate = 0.05,
        )

        assertEquals(expectedVolatility, greeks.impliedVolatility, 1e-6)
        assertTrue(greeks.gamma > 0.0)
        assertTrue(greeks.gamma < 0.1)
    }

    @Test
    fun `call and put gamma are identical for the same inputs`() {
        val volatility = 0.35
        val callPrice = BlackScholes.optionPrice(OptionType.CE, 110.0, 100.0, 0.25, 0.06, volatility)
        val putPrice = BlackScholes.optionPrice(OptionType.PE, 110.0, 100.0, 0.25, 0.06, volatility)
        val call = BlackScholes.calculateGreeks(OptionType.CE, callPrice, 110.0, 100.0, 0.25, 0.06)
        val put = BlackScholes.calculateGreeks(OptionType.PE, putPrice, 110.0, 100.0, 0.25, 0.06)

        assertEquals(volatility, call.impliedVolatility, 1e-6)
        assertEquals(volatility, put.impliedVolatility, 1e-6)
        assertEquals(call.gamma, put.gamma, 1e-10)
    }
}
