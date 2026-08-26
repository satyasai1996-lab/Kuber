package ai.kuber.core.market.options

import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.Quote
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

class OptionChainValidationException(
    val reasons: List<String>,
) : IllegalArgumentException(reasons.joinToString(separator = "; "))

/**
 * Fails closed before any Greeks or GEX calculation sees provider data.
 * Provider adapters are responsible for converting IV percentages to decimals.
 */
class OptionChainValidator(
    private val maximumIv: Double = 5.0,
    private val maximumFutureSkewMillis: Long = 60_000,
) {
    fun validate(
        quote: Quote,
        contracts: List<OptionContract>,
        now: Long,
        maxAgeMillis: Long,
    ): List<OptionContract> {
        require(maxAgeMillis > 0) { "maxAgeMillis must be positive" }
        val reasons = mutableListOf<String>()
        val normalizedSymbol = quote.symbol.normalizedSymbol()

        if (normalizedSymbol.isBlank()) reasons += "quote symbol is required"
        if (!quote.lastPrice.isFinite() || quote.lastPrice <= 0.0) {
            reasons += "quote price must be finite and positive"
        }
        if (quote.source.isBlank()) reasons += "quote source is required"
        if (quote.capturedAt <= 0) reasons += "quote capturedAt must be positive"
        if (quote.capturedAt > now + maximumFutureSkewMillis) {
            reasons += "quote capturedAt is in the future"
        }
        if (now - quote.capturedAt > maxAgeMillis) reasons += "quote is stale"
        val quoteVolume = quote.volume
        val quoteVwap = quote.vwap
        if (quoteVolume != null && quoteVolume < 0) reasons += "quote volume cannot be negative"
        if (quoteVwap != null && (!quoteVwap.isFinite() || quoteVwap <= 0.0)) {
            reasons += "quote VWAP must be finite and positive"
        }
        if (contracts.isEmpty()) reasons += "option chain cannot be empty"

        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val seenContracts = mutableSetOf<Triple<String, Double, String>>()
        contracts.forEachIndexed { index, contract ->
            val label = "option[$index]"
            val underlying = contract.underlying.normalizedSymbol()
            if (underlying.isBlank()) reasons += "$label underlying is required"
            if (underlying != normalizedSymbol) reasons += "$label has mixed underlying ${contract.underlying}"
            if (contract.source.isBlank()) reasons += "$label source is required"
            if (!contract.source.equals(quote.source, ignoreCase = true)) {
                reasons += "$label source does not match quote source"
            }
            if (!contract.strike.isFinite() || contract.strike <= 0.0) {
                reasons += "$label strike must be finite and positive"
            }
            if (contract.openInterest < 0) reasons += "$label open interest cannot be negative"
            if (!contract.impliedVolatility.isFinite() ||
                contract.impliedVolatility <= 0.0 ||
                contract.impliedVolatility > maximumIv
            ) {
                reasons += "$label IV must be in (0, $maximumIv]"
            }
            if (!contract.gamma.isFinite() || contract.gamma < 0.0) {
                reasons += "$label gamma must be finite and non-negative"
            }
            if (contract.lotSize <= 0) reasons += "$label lot size must be positive"
            if (!contract.lastPrice.isFinite() || contract.lastPrice <= 0.0) {
                reasons += "$label price must be finite and positive"
            }
            if (contract.volume < 0) reasons += "$label volume cannot be negative"
            if (contract.capturedAt <= 0) reasons += "$label capturedAt must be positive"
            if (contract.capturedAt > now + maximumFutureSkewMillis) {
                reasons += "$label capturedAt is in the future"
            }
            if (now - contract.capturedAt > maxAgeMillis) reasons += "$label is stale"

            val expiry = parseExpiry(contract.expiry, label, reasons)
            if (expiry != null && expiry < today) reasons += "$label expiry has passed"

            val duplicateKey = Triple(contract.expiry, contract.strike, contract.optionType.name)
            if (!seenContracts.add(duplicateKey)) reasons += "$label duplicates an option contract"
        }

        if (reasons.isNotEmpty()) throw OptionChainValidationException(reasons.toList())
        return contracts.toList()
    }

    private fun parseExpiry(
        rawExpiry: String,
        label: String,
        reasons: MutableList<String>,
    ): LocalDate? {
        if (rawExpiry.isBlank()) {
            reasons += "$label expiry is required"
            return null
        }
        return try {
            LocalDate.parse(rawExpiry)
        } catch (_: DateTimeParseException) {
            reasons += "$label expiry must use ISO-8601 yyyy-MM-dd"
            null
        }
    }

    private fun String.normalizedSymbol(): String = trim().uppercase(Locale.ROOT)
}
