package ai.kuber.core.agents

import ai.kuber.core.model.analysis.AnalystName
import ai.kuber.core.model.analysis.AnalystResult
import ai.kuber.core.model.analysis.Bias
import ai.kuber.core.model.market.GexRegime
import ai.kuber.core.model.market.MarketIntelligence
import ai.kuber.core.model.market.OptionsAnalytics
import java.util.Locale

data class AnalysisContext(
    val market: MarketIntelligence,
    val options: OptionsAnalytics,
    val fundamentalScore: Double? = null,
    val newsMacroScore: Double? = null,
    val sentimentScore: Double? = null,
    val sectorRotationScore: Double? = null,
)

interface Analyst {
    val name: AnalystName
    fun analyze(context: AnalysisContext): AnalystResult
}

internal fun result(
    context: AnalysisContext,
    name: AnalystName,
    score: Double,
    confidence: Int,
    evidence: List<String>,
    risks: List<String> = emptyList(),
): AnalystResult = AnalystResult(
    analyst = name,
    bias = when {
        score > 10.0 -> Bias.BULLISH
        score < -10.0 -> Bias.BEARISH
        else -> Bias.NEUTRAL
    },
    confidence = confidence.coerceIn(0, 100),
    score = score.coerceIn(-100.0, 100.0),
    evidence = evidence,
    risks = risks,
    snapshotId = context.market.snapshotId,
    inputVersion = context.market.inputVersion,
    inputCapturedAt = context.market.capturedAt,
)

internal fun unavailable(context: AnalysisContext, name: AnalystName, reason: String): AnalystResult = AnalystResult(
    analyst = name,
    bias = Bias.UNAVAILABLE,
    confidence = 0,
    score = 0.0,
    evidence = listOf(reason),
    risks = emptyList(),
    snapshotId = context.market.snapshotId,
    inputVersion = context.market.inputVersion,
    inputCapturedAt = context.market.capturedAt,
)

class TechnicalAnalyst : Analyst {
    override val name = AnalystName.TECHNICAL
    override fun analyze(context: AnalysisContext): AnalystResult {
        val quote = context.market.quote
        val vwap = quote.vwap ?: return unavailable(context, name, "VWAP is unavailable from the selected market provider")
        val score = ((quote.lastPrice - vwap) / vwap * 1_000.0).coerceIn(-100.0, 100.0)
        return result(context, name, score, (35 + kotlin.math.abs(score).toInt()).coerceAtMost(90), listOf(
            "Spot ${"%.2f".format(Locale.ROOT, quote.lastPrice)}",
            "VWAP ${"%.2f".format(Locale.ROOT, vwap)}",
        ))
    }
}

class ContextScoreAnalyst(
    override val name: AnalystName,
    private val label: String,
    private val scoreProvider: (AnalysisContext) -> Double?,
) : Analyst {
    override fun analyze(context: AnalysisContext): AnalystResult {
        val score = scoreProvider(context) ?: return unavailable(context, name, "$label source is not configured")
        return result(context, name, score, (30 + kotlin.math.abs(score).toInt()).coerceAtMost(80), listOf("$label score: ${"%.1f".format(Locale.ROOT, score)}"))
    }
}

class OptionsAnalyst : Analyst {
    override val name = AnalystName.OPTIONS
    override fun analyze(context: AnalysisContext): AnalystResult {
        val gex = context.market.gexSnapshot
        val pcr = context.options.putCallRatios.openInterest
        val score = when {
            pcr == null -> 0.0
            pcr > 1.08 -> 35.0
            pcr < 0.92 -> -35.0
            else -> 0.0
        }
        val evidence = mutableListOf(
            "GEX ${gex.regime}; total ${"%.2f".format(Locale.ROOT, gex.totalGex)}",
            "Gamma flip ${gex.gammaFlip?.let { "%.2f".format(Locale.ROOT, it) } ?: "unavailable"}",
            "OI PCR ${pcr?.let { "%.2f".format(Locale.ROOT, it) } ?: "unavailable"}",
        )
        context.options.maxCallOpenInterestStrike?.let { evidence += "Call OI wall ${"%.2f".format(Locale.ROOT, it)}" }
        context.options.maxPutOpenInterestStrike?.let { evidence += "Put OI wall ${"%.2f".format(Locale.ROOT, it)}" }
        val risks = buildList { if (gex.regime == GexRegime.NEGATIVE) add("Negative gamma can amplify moves") }
        return result(context, name, score, if (pcr == null) 40 else 80, evidence, risks)
    }
}

class RiskAnalyst : Analyst {
    override val name = AnalystName.RISK_MANAGER
    override fun analyze(context: AnalysisContext): AnalystResult {
        val gex = context.market.gexSnapshot
        val risks = buildList {
            if (context.market.freshness.name == "STALE") add("Market/GEX snapshot is stale")
            if (gex.regime == GexRegime.NEGATIVE) add("Negative gamma requires reduced position size")
        }
        return result(context, name, 0.0, 100, listOf("Independent risk evidence collected"), risks)
    }
}

fun defaultAnalysts(): List<Analyst> = listOf(
    TechnicalAnalyst(),
    ContextScoreAnalyst(AnalystName.FUNDAMENTAL, "Fundamental", AnalysisContext::fundamentalScore),
    OptionsAnalyst(),
    ContextScoreAnalyst(AnalystName.NEWS_MACRO, "News/macro", AnalysisContext::newsMacroScore),
    ContextScoreAnalyst(AnalystName.SENTIMENT, "Sentiment", AnalysisContext::sentimentScore),
    ContextScoreAnalyst(AnalystName.SECTOR_ROTATION, "Sector rotation", AnalysisContext::sectorRotationScore),
    RiskAnalyst(),
)
