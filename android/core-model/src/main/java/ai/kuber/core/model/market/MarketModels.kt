package ai.kuber.core.model.market

import kotlinx.serialization.Serializable

/** Provider-normalized option side. No free-form broker value enters the core. */
@Serializable
enum class OptionType {
    CE,
    PE,
}

/** Freshness recorded when an immutable market snapshot is constructed. */
@Serializable
enum class DataFreshness {
    FRESH,
    STALE,
}

/**
 * A normalized spot quote. [capturedAt] is UTC epoch milliseconds supplied by
 * the market-data provider (or assigned at the provider boundary).
 */
@Serializable
data class Quote(
    val symbol: String,
    val lastPrice: Double,
    val capturedAt: Long,
    val source: String,
    val volume: Long? = null,
    val vwap: Double? = null,
)

/** A normalized option row. IV is expressed as a decimal (0.20 = 20%). */
@Serializable
data class OptionContract(
    val underlying: String,
    val strike: Double,
    val expiry: String,
    val optionType: OptionType,
    val openInterest: Long,
    val impliedVolatility: Double,
    val gamma: Double,
    val lastPrice: Double,
    val lotSize: Int,
    val capturedAt: Long,
    val source: String,
    val volume: Long = 0,
    /** Provider-reported OI delta. Null means the provider did not supply it. */
    val openInterestChange: Long? = null,
)

/**
 * The sole market input accepted by downstream analysis engines.
 *
 * [snapshotId] identifies this immutable snapshot. [inputVersion] is copied
 * unchanged into GEX, analysis, plans, risk decisions and orders so a newer
 * quote can never be mixed with an older calculation.
 */
@Serializable
data class MarketIntelligence(
    val snapshotId: String,
    val inputVersion: String,
    val capturedAt: Long,
    val source: String,
    val freshness: DataFreshness,
    val quote: Quote,
    val optionChain: List<OptionContract>,
    val gexSnapshot: GexSnapshot,
) {
    init {
        require(snapshotId.isNotBlank()) { "snapshotId is required" }
        require(inputVersion.isNotBlank()) { "inputVersion is required" }
        require(gexSnapshot.snapshotId == snapshotId) {
            "GEX and market snapshot IDs must match"
        }
        require(gexSnapshot.inputVersion == inputVersion) {
            "GEX and market input versions must match"
        }
        require(gexSnapshot.capturedAt == capturedAt) {
            "GEX and market capture timestamps must match"
        }
        require(gexSnapshot.source == source) {
            "GEX and market sources must match"
        }
        require(gexSnapshot.freshness == freshness) {
            "GEX and market freshness must match"
        }
    }

    fun isStale(now: Long, maxAgeMillis: Long): Boolean {
        require(maxAgeMillis >= 0) { "maxAgeMillis cannot be negative" }
        return freshness == DataFreshness.STALE || now - capturedAt > maxAgeMillis
    }
}
