package ai.kuber.core.market.intelligence

import ai.kuber.core.market.gex.GexCalculator
import ai.kuber.core.market.options.OptionChainValidator
import ai.kuber.core.model.market.DataFreshness
import ai.kuber.core.model.market.MarketIntelligence
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.Quote
import java.util.Locale
import java.util.UUID

/**
 * The only constructor for authoritative market intelligence.
 * Validation and GEX each run once, and both outputs share one immutable version.
 */
class MarketIntelligenceBuilder(
    private val validator: OptionChainValidator = OptionChainValidator(),
    private val gexCalculator: GexCalculator = GexCalculator(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) {
    init {
        require(maxAgeMillis > 0) { "maxAgeMillis must be positive" }
    }

    fun build(
        quote: Quote,
        optionChain: List<OptionContract>,
    ): MarketIntelligence {
        val now = clock()
        val validatedChain = validator.validate(
            quote = quote,
            contracts = optionChain,
            now = now,
            maxAgeMillis = maxAgeMillis,
        )
        val capturedAt = minOf(quote.capturedAt, validatedChain.minOf(OptionContract::capturedAt))
        val inputVersion = idGenerator().also {
            require(it.isNotBlank()) { "generated inputVersion cannot be blank" }
        }
        val snapshotId = idGenerator().also {
            require(it.isNotBlank()) { "generated snapshotId cannot be blank" }
        }
        val source = quote.source.trim()
        val freshness = if (now - capturedAt > maxAgeMillis) {
            DataFreshness.STALE
        } else {
            DataFreshness.FRESH
        }
        check(freshness == DataFreshness.FRESH) { "validated market input became stale" }

        val gexSnapshot = gexCalculator.calculate(
            snapshotId = snapshotId,
            inputVersion = inputVersion,
            capturedAt = capturedAt,
            source = source,
            freshness = freshness,
            symbol = quote.symbol.trim().uppercase(Locale.ROOT),
            spot = quote.lastPrice,
            contracts = validatedChain,
        )
        return MarketIntelligence(
            snapshotId = snapshotId,
            inputVersion = inputVersion,
            capturedAt = capturedAt,
            source = source,
            freshness = freshness,
            quote = quote.copy(symbol = quote.symbol.trim().uppercase(Locale.ROOT), source = source),
            optionChain = validatedChain.toList(),
            gexSnapshot = gexSnapshot,
        )
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS: Long = 30_000
    }
}
