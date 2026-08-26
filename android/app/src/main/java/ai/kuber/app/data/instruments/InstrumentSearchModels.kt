package ai.kuber.app.data.instruments

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JsonObject? = null,
)

@Serializable
data class InstrumentDto(
    val instrument_id: String,
    val exchange: String,
    val segment: String,
    val tradingsymbol: String,
    val display_name: String,
    val instrument_type: String,
    val underlying: String? = null,
    val expiry: String? = null,
    val strike: Double? = null,
    val option_type: String? = null,
    val lot_size: Int,
    val tick_size: Double,
    val currency: String = "INR",
    val catalog_version: String? = null,
    val as_of: String? = null,
)

@Serializable
data class InstrumentSearchPageDto(
    val items: List<InstrumentDto> = emptyList(),
    val next_cursor: String? = null,
    val catalog_version: String? = null,
    val as_of: String? = null,
)

@Serializable
data class InstrumentSearchEnvelopeDto(
    val schema_version: String,
    val request_id: String? = null,
    val server_time: String? = null,
    val data: InstrumentSearchPageDto? = null,
    val error: ApiErrorDto? = null,
)

enum class InstrumentExchange(val wireName: String) {
    NSE("NSE"),
    BSE("BSE"),
    MCX("MCX"),
}

enum class InstrumentType(val wireName: String) {
    EQUITY("EQ"),
    INDEX("INDEX"),
    FUTURE("FUT"),
    CALL("CE"),
    PUT("PE"),
}

data class InstrumentSearchQuery(
    val text: String,
    val exchanges: Set<InstrumentExchange> = InstrumentExchange.entries.toSet(),
    val types: Set<InstrumentType> = emptySet(),
    val limit: Int = 25,
    val cursor: String? = null,
) {
    init {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(exchanges.isNotEmpty()) { "at least one exchange is required" }
    }

    val normalizedText: String
        get() = text.trim()
}

sealed interface InstrumentSearchState {
    data object Idle : InstrumentSearchState

    data class Loading(val query: InstrumentSearchQuery) : InstrumentSearchState

    data class Results(
        val query: InstrumentSearchQuery,
        val instruments: List<InstrumentDto>,
        val nextCursor: String?,
        val catalogVersion: String?,
        val asOf: String?,
    ) : InstrumentSearchState

    data class Empty(
        val query: InstrumentSearchQuery,
        val catalogVersion: String? = null,
        val asOf: String? = null,
    ) : InstrumentSearchState

    data class Error(
        val query: InstrumentSearchQuery,
        val code: String,
        val message: String,
        val retryable: Boolean,
        val httpStatus: Int? = null,
    ) : InstrumentSearchState
}
