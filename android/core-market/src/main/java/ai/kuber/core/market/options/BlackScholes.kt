package ai.kuber.core.market.options

import ai.kuber.core.model.market.OptionType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class BlackScholesGreeks(
    val impliedVolatility: Double,
    val gamma: Double,
)

/** Deterministic Black-Scholes pricing, implied-volatility and gamma functions. */
object BlackScholes {
    private const val MIN_VOLATILITY = 1e-6
    private const val MAX_VOLATILITY = 5.0
    private const val PRICE_TOLERANCE = 1e-8

    fun calculateGreeks(
        optionType: OptionType,
        marketPrice: Double,
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        riskFreeRate: Double,
    ): BlackScholesGreeks {
        val impliedVolatility = impliedVolatility(
            optionType = optionType,
            marketPrice = marketPrice,
            spot = spot,
            strike = strike,
            timeToExpiryYears = timeToExpiryYears,
            riskFreeRate = riskFreeRate,
        )
        return BlackScholesGreeks(
            impliedVolatility = impliedVolatility,
            gamma = gamma(
                spot = spot,
                strike = strike,
                timeToExpiryYears = timeToExpiryYears,
                riskFreeRate = riskFreeRate,
                volatility = impliedVolatility,
            ),
        )
    }

    fun optionPrice(
        optionType: OptionType,
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        riskFreeRate: Double,
        volatility: Double,
    ): Double {
        validateInputs(spot, strike, timeToExpiryYears, riskFreeRate, volatility)
        val sqrtTime = sqrt(timeToExpiryYears)
        val d1 = (
            ln(spot / strike) +
                (riskFreeRate + 0.5 * volatility * volatility) * timeToExpiryYears
            ) / (volatility * sqrtTime)
        val d2 = d1 - volatility * sqrtTime
        val discountedStrike = strike * exp(-riskFreeRate * timeToExpiryYears)
        return when (optionType) {
            OptionType.CE -> spot * normalCdf(d1) - discountedStrike * normalCdf(d2)
            OptionType.PE -> discountedStrike * normalCdf(-d2) - spot * normalCdf(-d1)
        }
    }

    fun gamma(
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        riskFreeRate: Double,
        volatility: Double,
    ): Double {
        validateInputs(spot, strike, timeToExpiryYears, riskFreeRate, volatility)
        val sqrtTime = sqrt(timeToExpiryYears)
        val d1 = (
            ln(spot / strike) +
                (riskFreeRate + 0.5 * volatility * volatility) * timeToExpiryYears
            ) / (volatility * sqrtTime)
        return normalDensity(d1) / (spot * volatility * sqrtTime)
    }

    fun impliedVolatility(
        optionType: OptionType,
        marketPrice: Double,
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        riskFreeRate: Double,
    ): Double {
        require(marketPrice.isFinite() && marketPrice > 0.0) {
            "marketPrice must be finite and positive"
        }
        validateInputs(spot, strike, timeToExpiryYears, riskFreeRate, MIN_VOLATILITY)

        val discountedStrike = strike * exp(-riskFreeRate * timeToExpiryYears)
        val lowerBound = when (optionType) {
            OptionType.CE -> max(0.0, spot - discountedStrike)
            OptionType.PE -> max(0.0, discountedStrike - spot)
        }
        val upperBound = when (optionType) {
            OptionType.CE -> spot
            OptionType.PE -> discountedStrike
        }
        require(marketPrice + PRICE_TOLERANCE >= lowerBound &&
            marketPrice <= upperBound + PRICE_TOLERANCE
        ) {
            "marketPrice violates Black-Scholes arbitrage bounds"
        }

        val priceAtMinimum = optionPrice(
            optionType,
            spot,
            strike,
            timeToExpiryYears,
            riskFreeRate,
            MIN_VOLATILITY,
        )
        if (abs(marketPrice - priceAtMinimum) <= PRICE_TOLERANCE) return MIN_VOLATILITY

        val priceAtMaximum = optionPrice(
            optionType,
            spot,
            strike,
            timeToExpiryYears,
            riskFreeRate,
            MAX_VOLATILITY,
        )
        require(marketPrice <= priceAtMaximum + PRICE_TOLERANCE) {
            "implied volatility exceeds supported range"
        }

        var low = MIN_VOLATILITY
        var high = MAX_VOLATILITY
        repeat(160) {
            val middle = (low + high) / 2.0
            val calculatedPrice = optionPrice(
                optionType,
                spot,
                strike,
                timeToExpiryYears,
                riskFreeRate,
                middle,
            )
            if (abs(calculatedPrice - marketPrice) <= PRICE_TOLERANCE) return middle
            if (calculatedPrice < marketPrice) low = middle else high = middle
        }
        return (low + high) / 2.0
    }

    private fun validateInputs(
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        riskFreeRate: Double,
        volatility: Double,
    ) {
        require(spot.isFinite() && spot > 0.0) { "spot must be finite and positive" }
        require(strike.isFinite() && strike > 0.0) { "strike must be finite and positive" }
        require(timeToExpiryYears.isFinite() && timeToExpiryYears > 0.0) {
            "timeToExpiryYears must be finite and positive"
        }
        require(riskFreeRate.isFinite()) { "riskFreeRate must be finite" }
        require(volatility.isFinite() && volatility > 0.0) {
            "volatility must be finite and positive"
        }
    }

    private fun normalDensity(value: Double): Double =
        exp(-0.5 * value * value) / sqrt(2.0 * PI)

    /** Abramowitz-Stegun 7.1.26; deterministic and sufficiently precise for IV solving. */
    private fun normalCdf(value: Double): Double {
        val sign = if (value < 0.0) -1.0 else 1.0
        val x = abs(value) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val polynomial = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t -
            0.284496736) * t + 0.254829592) * t
        val erf = 1.0 - polynomial * exp(-x.pow(2.0))
        return 0.5 * (1.0 + sign * erf)
    }
}
