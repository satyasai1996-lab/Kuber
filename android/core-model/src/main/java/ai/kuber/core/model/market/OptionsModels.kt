package ai.kuber.core.model.market

import kotlinx.serialization.Serializable

/** Analytics values that can be explicitly unavailable for an input snapshot. */
@Serializable
enum class OptionsAnalyticsField {
    PUT_CALL_OPEN_INTEREST_RATIO,
    PUT_CALL_VOLUME_RATIO,
    AVERAGE_CALL_IV,
    AVERAGE_PUT_IV,
    PUT_MINUS_CALL_IV_SKEW,
    MAX_CALL_OPEN_INTEREST_STRIKE,
    MAX_PUT_OPEN_INTEREST_STRIKE,
    OPEN_INTEREST_CHANGE,
}

/** Reference to the authoritative GEX already attached to MarketIntelligence. */
@Serializable
data class GexSnapshotReference(
    val snapshotId: String,
    val inputVersion: String,
    val capturedAt: Long,
)

@Serializable
data class PutCallRatios(
    /** Total put OI divided by total call OI. */
    val openInterest: Double? = null,
    /** Total put volume divided by total call volume. */
    val volume: Double? = null,
)

/** One expiry/strike point in the provider IV smile. Null means that side is absent. */
@Serializable
data class IvSmilePoint(
    val expiry: String,
    val strike: Double,
    val callIv: Double? = null,
    val putIv: Double? = null,
    /** Put IV minus call IV; null unless both sides are available. */
    val putMinusCallSkew: Double? = null,
)

@Serializable
enum class OpenInterestChangeDirection {
    BUILDUP,
    UNWINDING,
    UNCHANGED,
}

/** A provider-sourced OI delta. No signal is emitted when the provider omitted it. */
@Serializable
data class OpenInterestChangeSignal(
    val expiry: String,
    val strike: Double,
    val optionType: OptionType,
    val change: Long,
    val direction: OpenInterestChangeDirection,
)

@Serializable
enum class OptionsAnomalyKind {
    EXTREME_OPEN_INTEREST_PCR,
    EXTREME_VOLUME_PCR,
    IV_SKEW,
    LARGE_OPEN_INTEREST_CHANGE,
}

/** A deterministic observation, not a trade recommendation. */
@Serializable
data class OptionsAnomaly(
    val kind: OptionsAnomalyKind,
    val observedValue: Double,
    val expiry: String? = null,
    val strike: Double? = null,
    val optionType: OptionType? = null,
)

/**
 * Options analytics bound to one validated MarketIntelligence version.
 *
 * This object references the precomputed authoritative GEX; it does not carry,
 * recalculate or replace any GEX values.
 */
@Serializable
data class OptionsAnalytics(
    val snapshotId: String,
    val inputVersion: String,
    val capturedAt: Long,
    val source: String,
    val freshness: DataFreshness,
    val symbol: String,
    val gexReference: GexSnapshotReference,
    val putCallRatios: PutCallRatios,
    val averageCallIv: Double? = null,
    val averagePutIv: Double? = null,
    val putMinusCallIvSkew: Double? = null,
    val ivSmile: List<IvSmilePoint> = emptyList(),
    val maxCallOpenInterestStrike: Double? = null,
    val maxPutOpenInterestStrike: Double? = null,
    val openInterestChangeSignals: List<OpenInterestChangeSignal> = emptyList(),
    val openInterestChangeAvailableContracts: Int = 0,
    val openInterestChangeTotalContracts: Int = 0,
    val anomalies: List<OptionsAnomaly> = emptyList(),
    val unavailableFields: List<OptionsAnalyticsField> = emptyList(),
) {
    init {
        require(snapshotId.isNotBlank()) { "snapshotId is required" }
        require(inputVersion.isNotBlank()) { "inputVersion is required" }
        require(gexReference.snapshotId == snapshotId) {
            "Options analytics and GEX snapshot IDs must match"
        }
        require(gexReference.inputVersion == inputVersion) {
            "Options analytics and GEX input versions must match"
        }
        require(gexReference.capturedAt == capturedAt) {
            "Options analytics and GEX capture timestamps must match"
        }
        require(openInterestChangeAvailableContracts >= 0) {
            "available OI-change contract count cannot be negative"
        }
        require(openInterestChangeTotalContracts >= openInterestChangeAvailableContracts) {
            "total OI-change contract count cannot be less than available count"
        }
    }

    fun isAvailable(field: OptionsAnalyticsField): Boolean = field !in unavailableFields
}
