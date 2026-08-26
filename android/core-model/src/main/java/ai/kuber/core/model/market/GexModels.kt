package ai.kuber.core.model.market

import kotlinx.serialization.Serializable

@Serializable
enum class GexRegime {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
}

/** Dealer gamma exposure aggregated across all selected expiries at a strike. */
@Serializable
data class GexStrike(
    val strike: Double,
    val callGex: Double,
    val putGex: Double,
    val netGex: Double,
)

/** Dealer gamma exposure aggregated for one expiry. */
@Serializable
data class GexExpiry(
    val expiry: String,
    val callGex: Double,
    val putGex: Double,
    val netGex: Double,
)

/** One GEX calculation bound to exactly one normalized input version. */
@Serializable
data class GexSnapshot(
    val snapshotId: String,
    val inputVersion: String,
    val capturedAt: Long,
    val source: String,
    val freshness: DataFreshness,
    val symbol: String,
    val spot: Double,
    val expirySet: List<String>,
    val gexByStrike: List<GexStrike>,
    val gexByExpiry: List<GexExpiry>,
    val totalGex: Double,
    val gammaFlip: Double?,
    val callWall: Double?,
    val putWall: Double?,
    val gammaWalls: List<Double>,
    val regime: GexRegime,
) {
    init {
        require(snapshotId.isNotBlank()) { "snapshotId is required" }
        require(inputVersion.isNotBlank()) { "inputVersion is required" }
    }

    fun isStale(now: Long, maxAgeMillis: Long): Boolean {
        require(maxAgeMillis >= 0) { "maxAgeMillis cannot be negative" }
        return freshness == DataFreshness.STALE || now - capturedAt > maxAgeMillis
    }
}
