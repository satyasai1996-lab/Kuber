package ai.kuber.core.market.gex

import ai.kuber.core.model.market.DataFreshness
import ai.kuber.core.model.market.GexExpiry
import ai.kuber.core.model.market.GexRegime
import ai.kuber.core.model.market.GexSnapshot
import ai.kuber.core.model.market.GexStrike
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import kotlin.math.abs
import kotlin.math.round

/** Port of india-trade-cli analysis/gex.py using the same dealer-short convention. */
open class GexCalculator(
    private val regimeThreshold: Double = 50.0,
) {
    /** Exact upstream formula: OI * gamma * spot * lot * 100, puts negated. */
    fun exposure(
        openInterest: Long,
        gamma: Double,
        spot: Double,
        lotSize: Int,
        optionType: OptionType,
    ): Double {
        val unsigned = openInterest * gamma * spot * lotSize * 100.0
        return if (optionType == OptionType.CE) unsigned else -unsigned
    }

    open fun calculate(
        snapshotId: String,
        inputVersion: String,
        capturedAt: Long,
        source: String,
        freshness: DataFreshness,
        symbol: String,
        spot: Double,
        contracts: List<OptionContract>,
    ): GexSnapshot {
        require(snapshotId.isNotBlank()) { "snapshotId is required" }
        require(inputVersion.isNotBlank()) { "inputVersion is required" }
        require(source.isNotBlank()) { "source is required" }
        require(symbol.isNotBlank()) { "symbol is required" }
        require(spot.isFinite() && spot > 0.0) { "spot must be finite and positive" }
        require(contracts.isNotEmpty()) { "option chain cannot be empty" }

        val strikeTotals = sortedMapOf<Double, ExposureBucket>()
        val expiryTotals = sortedMapOf<String, ExposureBucket>()
        contracts.forEach { contract ->
            val value = exposure(
                openInterest = contract.openInterest,
                gamma = contract.gamma,
                spot = spot,
                lotSize = contract.lotSize,
                optionType = contract.optionType,
            )
            strikeTotals.getOrPut(contract.strike, ::ExposureBucket).add(contract.optionType, value)
            expiryTotals.getOrPut(contract.expiry, ::ExposureBucket).add(contract.optionType, value)
        }

        val byStrike = strikeTotals.map { (strike, bucket) ->
            GexStrike(
                strike = strike,
                callGex = roundToCents(bucket.call),
                putGex = roundToCents(bucket.put),
                netGex = roundToCents(bucket.call + bucket.put),
            )
        }
        val byExpiry = expiryTotals.map { (expiry, bucket) ->
            GexExpiry(
                expiry = expiry,
                callGex = roundToCents(bucket.call),
                putGex = roundToCents(bucket.put),
                netGex = roundToCents(bucket.call + bucket.put),
            )
        }
        val totalGex = roundToCents(byStrike.sumOf(GexStrike::netGex))
        val callWall = byStrike
            .filter { it.callGex > 0.0 }
            .sortedWith(compareByDescending<GexStrike> { it.callGex }.thenBy { it.strike })
            .firstOrNull()
            ?.strike
        val putWall = byStrike
            .filter { it.putGex < 0.0 }
            .sortedWith(compareBy<GexStrike> { it.putGex }.thenBy { it.strike })
            .firstOrNull()
            ?.strike
        val gammaWalls = byStrike
            .filter { it.netGex != 0.0 }
            .sortedWith(compareByDescending<GexStrike> { abs(it.netGex) }.thenBy { it.strike })
            .take(3)
            .map(GexStrike::strike)

        return GexSnapshot(
            snapshotId = snapshotId,
            inputVersion = inputVersion,
            capturedAt = capturedAt,
            source = source,
            freshness = freshness,
            symbol = symbol,
            spot = spot,
            expirySet = byExpiry.map(GexExpiry::expiry),
            gexByStrike = byStrike,
            gexByExpiry = byExpiry,
            totalGex = totalGex,
            gammaFlip = findPositiveToNonPositiveFlip(byStrike),
            callWall = callWall,
            putWall = putWall,
            gammaWalls = gammaWalls,
            regime = when {
                totalGex > regimeThreshold -> GexRegime.POSITIVE
                totalGex < -regimeThreshold -> GexRegime.NEGATIVE
                else -> GexRegime.NEUTRAL
            },
        )
    }

    /** Matches upstream: only the first positive-to-nonpositive crossing is a flip. */
    fun findPositiveToNonPositiveFlip(strikes: List<GexStrike>): Double? {
        strikes.zipWithNext().forEach { (previous, current) ->
            if (previous.netGex > 0.0 && current.netGex <= 0.0) {
                if (previous.netGex == current.netGex) return current.strike
                val ratio = previous.netGex / (previous.netGex - current.netGex)
                return roundToCents(previous.strike + ratio * (current.strike - previous.strike))
            }
        }
        return null
    }

    private fun roundToCents(value: Double): Double = round(value * 100.0) / 100.0

    private data class ExposureBucket(
        var call: Double = 0.0,
        var put: Double = 0.0,
    ) {
        fun add(optionType: OptionType, value: Double) {
            if (optionType == OptionType.CE) call += value else put += value
        }
    }
}
