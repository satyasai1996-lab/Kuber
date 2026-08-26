package ai.kuber.core.market.options

import ai.kuber.core.market.FIXED_NOW
import ai.kuber.core.market.option
import ai.kuber.core.market.quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionChainValidatorTest {
    private val validator = OptionChainValidator()

    @Test
    fun `accepts one fresh normalized chain`() {
        val chain = listOf(option())

        val validated = validator.validate(quote(), chain, FIXED_NOW, 30_000)

        assertEquals(chain, validated)
        assertTrue(validated !== chain)
    }

    @Test
    fun `rejects a missing chain and mixed symbols`() {
        assertThrows(OptionChainValidationException::class.java) {
            validator.validate(quote(), emptyList(), FIXED_NOW, 30_000)
        }
        val mixed = listOf(option(), option(underlying = "BANKNIFTY", strike = 22_100.0))
        val error = assertThrows(OptionChainValidationException::class.java) {
            validator.validate(quote(), mixed, FIXED_NOW, 30_000)
        }
        assertTrue(error.reasons.any { "mixed underlying" in it })
    }

    @Test
    fun `rejects invalid strike expiry OI IV gamma lot and price`() {
        val invalidRows = listOf(
            option(strike = 0.0),
            option(expiry = "31-12-2099"),
            option(openInterest = -1),
            option(impliedVolatility = 0.0),
            option(impliedVolatility = Double.NaN),
            option(gamma = -0.01),
            option(gamma = Double.NaN),
            option(lotSize = 0),
            option(lastPrice = 0.0),
            option(lastPrice = Double.POSITIVE_INFINITY),
        )

        invalidRows.forEach { invalid ->
            assertThrows(OptionChainValidationException::class.java) {
                validator.validate(quote(), listOf(invalid), FIXED_NOW, 30_000)
            }
        }
    }

    @Test
    fun `rejects stale quote or option data`() {
        assertThrows(OptionChainValidationException::class.java) {
            validator.validate(
                quote(capturedAt = FIXED_NOW - 30_001),
                listOf(option()),
                FIXED_NOW,
                30_000,
            )
        }
        val error = assertThrows(OptionChainValidationException::class.java) {
            validator.validate(
                quote(),
                listOf(option(capturedAt = FIXED_NOW - 30_001)),
                FIXED_NOW,
                30_000,
            )
        }
        assertTrue(error.reasons.any { "is stale" in it })
    }
}
