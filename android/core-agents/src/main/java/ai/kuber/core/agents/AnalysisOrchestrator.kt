package ai.kuber.core.agents

import ai.kuber.core.model.analysis.AnalysisResult
import ai.kuber.core.model.analysis.AnalystName
import ai.kuber.core.model.analysis.AnalystResult
import ai.kuber.core.model.analysis.Bias
import ai.kuber.core.model.analysis.ConflictRecord
import ai.kuber.core.model.analysis.Debate
import ai.kuber.core.model.analysis.FundManagerDecision
import ai.kuber.core.model.analysis.GexAlignment
import ai.kuber.core.model.analysis.PipelineStage
import ai.kuber.core.model.analysis.RiskProfile
import ai.kuber.core.model.analysis.Scorecard
import ai.kuber.core.model.analysis.TradePlan
import ai.kuber.core.model.market.GexRegime
import ai.kuber.core.risk.RiskGate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Ordered authority pipeline. Only the seven independent evidence agents run
 * concurrently; every stage after schema validation is intentionally ordered.
 */
class AnalysisOrchestrator(
    private val analysts: List<Analyst> = defaultAnalysts(),
    private val riskGate: RiskGate = RiskGate(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    init { require(analysts.map(Analyst::name).toSet() == AnalystName.entries.toSet()) { "exactly seven unique analysts are required" } }

    fun analyze(context: AnalysisContext, now: Long = System.currentTimeMillis()): AnalysisResult {
        val reports = runIndependentAnalysts(context)
        validateSchemas(context, reports)
        val scorecard = buildScorecard(reports)
        val conflicts = detectConflicts(reports)
        val bull = buildArgument(reports, Bias.BULLISH, "Bull case")
        val bear = buildArgument(reports, Bias.BEARISH, "Bear case")
        val bullRebuttal = "Bull rebuttal: ${bear.takeIf { reports.any { r -> r.bias == Bias.BEARISH } } ?: "no bearish evidence to rebut"}"
        val bearRebuttal = "Bear rebuttal: ${bull.takeIf { reports.any { r -> r.bias == Bias.BULLISH } } ?: "no bullish evidence to rebut"}"
        val facilitator = "Facilitator: weighted score ${"%.2f".format(scorecard.weightedScore)}, ${scorecard.agreementPercent}% agreement; ${conflicts.size} conflict record(s)."
        val debate = Debate(bull, bear, bullRebuttal, bearRebuttal, facilitator)
        val fundManager = fundManager(context, scorecard, reports)
        val finalRisk = riskGate.evaluate(context.market, fundManager, scorecard.agreementPercent, now)
        val plans = createPlans(context, fundManager, finalRisk)
        return AnalysisResult(
            analysisId = idGenerator().also { require(it.isNotBlank()) },
            snapshotId = context.market.snapshotId,
            inputVersion = context.market.inputVersion,
            stages = PipelineStage.entries.toList(),
            analysts = reports,
            scorecard = scorecard,
            conflicts = conflicts,
            debate = debate,
            fundManager = fundManager,
            finalRisk = finalRisk,
            tradePlans = plans,
            finalBias = if (finalRisk.approved) fundManager.bias else Bias.NEUTRAL,
        )
    }

    private fun runIndependentAnalysts(context: AnalysisContext): List<AnalystResult> {
        val pool = Executors.newFixedThreadPool(analysts.size)
        return try {
            val futures = analysts.map { analyst -> pool.submit(Callable {
                try { analyst.analyze(context) } catch (error: Exception) { unavailable(context, analyst.name, "Analyst failed safely: ${error.javaClass.simpleName}") }
            }) }
            futures.map { it.get() }.sortedBy { it.analyst.ordinal }
        } finally { pool.shutdownNow() }
    }

    private fun validateSchemas(context: AnalysisContext, reports: List<AnalystResult>) {
        require(reports.size == AnalystName.entries.size) { "seven analyst schemas are required" }
        require(reports.map(AnalystResult::analyst).toSet() == AnalystName.entries.toSet()) { "analyst schemas must be unique" }
        require(reports.all { it.snapshotId == context.market.snapshotId && it.inputVersion == context.market.inputVersion && it.inputCapturedAt == context.market.capturedAt }) { "analyst schema version mismatch" }
        require(reports.all { it.confidence in 0..100 && it.score.isFinite() }) { "invalid analyst score schema" }
    }

    private fun buildScorecard(reports: List<AnalystResult>): Scorecard {
        val weights = mapOf(
            AnalystName.TECHNICAL to .25, AnalystName.FUNDAMENTAL to .20, AnalystName.OPTIONS to .15,
            AnalystName.NEWS_MACRO to .10, AnalystName.SENTIMENT to .10, AnalystName.SECTOR_ROTATION to .05,
            AnalystName.RISK_MANAGER to .15,
        )
        val active = reports.filter { it.bias != Bias.UNAVAILABLE }
        val totalWeight = active.sumOf { weights.getValue(it.analyst) }
        val weighted = if (totalWeight == 0.0) 0.0 else active.sumOf { it.score * weights.getValue(it.analyst) } / totalWeight
        val directional = active.filter { it.bias == Bias.BULLISH || it.bias == Bias.BEARISH }
        val agreement = if (directional.isEmpty()) 0.0 else maxOf(
            directional.count { it.bias == Bias.BULLISH }, directional.count { it.bias == Bias.BEARISH },
        ) * 100.0 / directional.size
        return Scorecard(weighted, biasFor(weighted), agreement, active.size)
    }

    private fun detectConflicts(reports: List<AnalystResult>): List<ConflictRecord> {
        val bulls = reports.filter { it.bias == Bias.BULLISH }.map(AnalystResult::analyst)
        val bears = reports.filter { it.bias == Bias.BEARISH }.map(AnalystResult::analyst)
        return if (bulls.isNotEmpty() && bears.isNotEmpty()) listOf(ConflictRecord(bulls, bears, "Bullish and bearish analysts disagree")) else emptyList()
    }

    private fun buildArgument(reports: List<AnalystResult>, bias: Bias, prefix: String): String {
        val names = reports.filter { it.bias == bias }.joinToString { it.analyst.name.lowercase().replace('_', ' ') }
        return "$prefix: ${if (names.isBlank()) "no supporting analyst" else names}"
    }

    private fun fundManager(context: AnalysisContext, scorecard: Scorecard, reports: List<AnalystResult>): FundManagerDecision {
        val alignment = when {
            scorecard.bias == Bias.NEUTRAL -> GexAlignment.NEUTRAL
            context.market.gexSnapshot.regime == GexRegime.NEGATIVE -> GexAlignment.SUPPORTS
            context.market.gexSnapshot.regime == GexRegime.POSITIVE -> GexAlignment.OPPOSES
            else -> GexAlignment.NEUTRAL
        }
        val options = reports.first { it.analyst == AnalystName.OPTIONS }
        return FundManagerDecision(
            bias = scorecard.bias,
            confidence = ((scorecard.agreementPercent + options.confidence) / 2.0).toInt().coerceIn(0, 100),
            gexAlignment = alignment,
            rationale = listOf("Scorecard ${scorecard.bias} (${"%.2f".format(scorecard.weightedScore)})", "GEX ${context.market.gexSnapshot.regime} $alignment the proposed direction"),
        )
    }

    private fun createPlans(context: AnalysisContext, fund: FundManagerDecision, risk: ai.kuber.core.model.analysis.RiskDecision): List<TradePlan> {
        val quote = context.market.quote.lastPrice
        val directionSign = if (fund.bias == Bias.BULLISH) 1 else if (fund.bias == Bias.BEARISH) -1 else 0
        fun plan(profile: RiskProfile, stopPercent: Double, targetPercent: Double): TradePlan {
            val enabled = risk.approved && directionSign != 0
            val entry = if (enabled) quote else null
            val stop = if (enabled) quote * (1 - directionSign * stopPercent) else null
            val target = if (enabled) quote * (1 + directionSign * targetPercent) else null
            return TradePlan(profile, if (enabled) fund.bias else Bias.NEUTRAL, entry, stop, listOfNotNull(target), if (enabled) risk.allowedQuantity else 0, if (enabled) targetPercent / stopPercent else null, fund.gexAlignment, risk.reasons, context.market.snapshotId, context.market.inputVersion)
        }
        return listOf(
            plan(RiskProfile.AGGRESSIVE, .01, .02), plan(RiskProfile.NEUTRAL, .02, .04), plan(RiskProfile.CONSERVATIVE, .03, .06),
        )
    }

    private fun biasFor(score: Double): Bias = when { score > 10.0 -> Bias.BULLISH; score < -10.0 -> Bias.BEARISH; else -> Bias.NEUTRAL }
}
