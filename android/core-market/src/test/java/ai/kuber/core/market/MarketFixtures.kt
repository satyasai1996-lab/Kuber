package ai.kuber.core.market

import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.Quote

const val FIXED_NOW: Long = 1_800_000_000_000

fun quote(
    symbol: String = "NIFTY",
    price: Double = 22_000.0,
    capturedAt: Long = FIXED_NOW - 1_000,
    source: String = "fixture",
): Quote = Quote(
    symbol = symbol,
    lastPrice = price,
    capturedAt = capturedAt,
    source = source,
)

fun option(
    underlying: String = "NIFTY",
    strike: Double = 22_000.0,
    expiry: String = "2099-12-31",
    optionType: OptionType = OptionType.CE,
    openInterest: Long = 100,
    impliedVolatility: Double = 0.2,
    gamma: Double = 0.01,
    lastPrice: Double = 100.0,
    lotSize: Int = 25,
    capturedAt: Long = FIXED_NOW - 1_000,
    source: String = "fixture",
    volume: Long = 10,
    openInterestChange: Long? = null,
): OptionContract = OptionContract(
    underlying = underlying,
    strike = strike,
    expiry = expiry,
    optionType = optionType,
    openInterest = openInterest,
    impliedVolatility = impliedVolatility,
    gamma = gamma,
    lastPrice = lastPrice,
    lotSize = lotSize,
    capturedAt = capturedAt,
    source = source,
    volume = volume,
    openInterestChange = openInterestChange,
)
