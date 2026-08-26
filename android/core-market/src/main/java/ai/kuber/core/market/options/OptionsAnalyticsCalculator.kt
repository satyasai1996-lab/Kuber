package ai.kuber.core.market.options

import ai.kuber.core.model.market.GexSnapshotReference
import ai.kuber.core.model.market.IvSmilePoint
import ai.kuber.core.model.market.MarketIntelligence
import ai.kuber.core.model.market.OpenInterestChangeDirection
import ai.kuber.core.model.market.OpenInterestChangeSignal
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.OptionsAnalytics
import ai.kuber.core.model.market.OptionsAnalyticsField
import ai.kuber.core.model.market.OptionsAnomaly
import ai.kuber.core.model.market.OptionsAnomalyKind
import ai.kuber.core.model.market.PutCallRatios
import kotlin.math.abs

/**
 * Derives options-only statistics from one already-validated market snapshot.
 *
 * GEX is intentionally not a dependency of this calculator. The returned
 * reference only proves which authoritative GEX version accompanied the input.
 */
class OptionsAnalyticsCalculator(
    private val extremePcrLow: Double = 0.67,
    private val extremePcrHigh: Double = 1.5,
    private val materialIvSkew: Double = 0.05,
    private val largeOpenInterestChangeRatio: Double = 0.25,
) {
    init {
        require(extremePcrLow >= 0.0) { "extremePcrLow cannot be negative" }
        require(extremePcrHigh > extremePcrLow) {
            "extremePcrHigh must be greater than extremePcrLow"
        }
        require(materialIvSkew >= 0.0) { "materialIvSkew cannot be negative" }
        require(largeOpenInterestChangeRatio >= 0.0) {
            "largeOpenInterestChangeRatio cannot be negative"
        }
    }

    fun calculate(market: MarketIntelligence): OptionsAnalytics {
        val gex = market.gexSnapshot
        require(gex.snapshotId == market.snapshotId && gex.inputVersion == market.inputVersion) {
            "Options analytics requires same-version authoritative GEX"
        }
        require(gex.capturedAt == market.capturedAt) {
            "Options analytics requires the same GEX capture timestamp"
        }

        val calls = market.optionChain.filter { it.optionType == OptionType.CE }
        val puts = market.optionChain.filter { it.optionType == OptionType.PE }
        val openInterestPcr = putCallRatio(calls, puts, OptionContract::openInterest)
        val volumePcr = putCallRatio(calls, puts, OptionContract::volume)
        val averageCallIv = calls.averageIvOrNull()
        val averagePutIv = puts.averageIvOrNull()
        val aggregateSkew = if (averageCallIv != null && averagePutIv != null) {
            averagePutIv - averageCallIv
        } else {
            null
        }
        val maxCallOiStrike = calls.maxOpenInterestStrikeOrNull(market.quote.lastPrice)
        val maxPutOiStrike = puts.maxOpenInterestStrikeOrNull(market.quote.lastPrice)
        val oiSignals = market.optionChain
            .mapNotNull { contract -> contract.toOpenInterestChangeSignalOrNull() }
            .sortedWith(
                compareBy<OpenInterestChangeSignal> { it.expiry }
                    .thenBy { it.strike }
                    .thenBy { it.optionType.name },
            )
        val ivSmile = market.optionChain
            .groupBy { SmileKey(it.expiry, it.strike) }
            .toSortedMap(compareBy<SmileKey> { it.expiry }.thenBy { it.strike })
            .map { (key, contracts) ->
                val callIv = contracts.filter { it.optionType == OptionType.CE }.averageIvOrNull()
                val putIv = contracts.filter { it.optionType == OptionType.PE }.averageIvOrNull()
                IvSmilePoint(
                    expiry = key.expiry,
                    strike = key.strike,
                    callIv = callIv,
                    putIv = putIv,
                    putMinusCallSkew = if (callIv != null && putIv != null) putIv - callIv else null,
                )
            }

        val unavailable = buildList {
            if (openInterestPcr == null) add(OptionsAnalyticsField.PUT_CALL_OPEN_INTEREST_RATIO)
            if (volumePcr == null) add(OptionsAnalyticsField.PUT_CALL_VOLUME_RATIO)
            if (averageCallIv == null) add(OptionsAnalyticsField.AVERAGE_CALL_IV)
            if (averagePutIv == null) add(OptionsAnalyticsField.AVERAGE_PUT_IV)
            if (aggregateSkew == null) add(OptionsAnalyticsField.PUT_MINUS_CALL_IV_SKEW)
            if (maxCallOiStrike == null) add(OptionsAnalyticsField.MAX_CALL_OPEN_INTEREST_STRIKE)
            if (maxPutOiStrike == null) add(OptionsAnalyticsField.MAX_PUT_OPEN_INTEREST_STRIKE)
            if (oiSignals.isEmpty()) add(OptionsAnalyticsField.OPEN_INTEREST_CHANGE)
        }
        val anomalies = buildList {
            openInterestPcr?.takeIf(::isExtremePcr)?.let {
                add(OptionsAnomaly(OptionsAnomalyKind.EXTREME_OPEN_INTEREST_PCR, it))
            }
            volumePcr?.takeIf(::isExtremePcr)?.let {
                add(OptionsAnomaly(OptionsAnomalyKind.EXTREME_VOLUME_PCR, it))
            }
            aggregateSkew?.takeIf { abs(it) >= materialIvSkew }?.let {
                add(OptionsAnomaly(OptionsAnomalyKind.IV_SKEW, it))
            }
            market.optionChain
                .sortedWith(
                    compareBy<OptionContract> { it.expiry }
                        .thenBy { it.strike }
                        .thenBy { it.optionType.name },
                )
                .forEach { contract ->
                    val change = contract.openInterestChange ?: return@forEach
                    if (contract.openInterest <= 0L) return@forEach
                    val ratio = abs(change.toDouble()) / contract.openInterest.toDouble()
                    if (ratio >= largeOpenInterestChangeRatio) {
                        add(
                            OptionsAnomaly(
                                kind = OptionsAnomalyKind.LARGE_OPEN_INTEREST_CHANGE,
                                observedValue = ratio,
                                expiry = contract.expiry,
                                strike = contract.strike,
                                optionType = contract.optionType,
                            ),
                        )
                    }
                }
        }

        return OptionsAnalytics(
            snapshotId = market.snapshotId,
            inputVersion = market.inputVersion,
            capturedAt = market.capturedAt,
            source = market.source,
            freshness = market.freshness,
            symbol = market.quote.symbol,
            gexReference = GexSnapshotReference(
                snapshotId = gex.snapshotId,
                inputVersion = gex.inputVersion,
                capturedAt = gex.capturedAt,
            ),
            putCallRatios = PutCallRatios(
                openInterest = openInterestPcr,
                volume = volumePcr,
            ),
            averageCallIv = averageCallIv,
            averagePutIv = averagePutIv,
            putMinusCallIvSkew = aggregateSkew,
            ivSmile = ivSmile,
            maxCallOpenInterestStrike = maxCallOiStrike,
            maxPutOpenInterestStrike = maxPutOiStrike,
            openInterestChangeSignals = oiSignals,
            openInterestChangeAvailableContracts = oiSignals.size,
            openInterestChangeTotalContracts = market.optionChain.size,
            anomalies = anomalies,
            unavailableFields = unavailable,
        )
    }

    private fun putCallRatio(
        calls: List<OptionContract>,
        puts: List<OptionContract>,
        value: (OptionContract) -> Long,
    ): Double? {
        if (calls.isEmpty() || puts.isEmpty()) return null
        val callTotal = calls.sumOf { value(it).toDouble() }
        if (callTotal <= 0.0) return null
        return puts.sumOf { value(it).toDouble() } / callTotal
    }

    private fun List<OptionContract>.averageIvOrNull(): Double? =
        takeIf { it.isNotEmpty() }?.map(OptionContract::impliedVolatility)?.average()

    private fun List<OptionContract>.maxOpenInterestStrikeOrNull(spot: Double): Double? =
        groupBy(OptionContract::strike)
            .map { (strike, contracts) -> strike to contracts.sumOf { it.openInterest.toDouble() } }
            .filter { (_, total) -> total > 0.0 }
            .sortedWith(
                compareByDescending<Pair<Double, Double>> { it.second }
                    .thenBy { abs(it.first - spot) }
                    .thenBy { it.first },
            )
            .firstOrNull()
            ?.first

    private fun OptionContract.toOpenInterestChangeSignalOrNull(): OpenInterestChangeSignal? {
        val change = openInterestChange ?: return null
        return OpenInterestChangeSignal(
            expiry = expiry,
            strike = strike,
            optionType = optionType,
            change = change,
            direction = when {
                change > 0L -> OpenInterestChangeDirection.BUILDUP
                change < 0L -> OpenInterestChangeDirection.UNWINDING
                else -> OpenInterestChangeDirection.UNCHANGED
            },
        )
    }

    private fun isExtremePcr(value: Double): Boolean =
        value <= extremePcrLow || value >= extremePcrHigh

    private data class SmileKey(
        val expiry: String,
        val strike: Double,
    )
}
