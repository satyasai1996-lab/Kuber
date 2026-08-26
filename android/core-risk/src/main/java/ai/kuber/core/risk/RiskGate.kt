package ai.kuber.core.risk

import ai.kuber.core.model.analysis.Bias
import ai.kuber.core.model.analysis.FundManagerDecision
import ai.kuber.core.model.analysis.RiskDecision
import ai.kuber.core.model.market.GexRegime
import ai.kuber.core.model.market.MarketIntelligence
import kotlin.math.floor

data class RiskSettings(
    val capital: Double = 200_000.0,
    val maxRiskPercent: Double = 1.0,
    val minimumAgreementPercent: Double = 50.0,
    val maxSnapshotAgeMillis: Long = 30_000L,
) {
    init {
        require(capital > 0 && capital.isFinite())
        require(maxRiskPercent > 0 && maxRiskPercent <= 5.0)
        require(minimumAgreementPercent in 0.0..100.0)
        require(maxSnapshotAgeMillis > 0)
    }
}

/** Final post-fund-manager veto. It is intentionally separate from the seventh Risk Analyst. */
class RiskGate(private val settings: RiskSettings = RiskSettings()) {
    fun evaluate(
        market: MarketIntelligence,
        decision: FundManagerDecision,
        agreementPercent: Double,
        now: Long = System.currentTimeMillis(),
    ): RiskDecision {
        val reasons = mutableListOf<String>()
        if (market.isStale(now, settings.maxSnapshotAgeMillis)) reasons += "Market/GEX snapshot is stale"
        if (decision.bias == Bias.NEUTRAL || decision.bias == Bias.UNAVAILABLE) reasons += "No directional fund-manager decision"
        if (agreementPercent < settings.minimumAgreementPercent) reasons += "Analyst agreement is below the configured minimum"
        if (market.gexSnapshot.regime == GexRegime.NEGATIVE) reasons += "Negative gamma requires reduced size"
        val maxRisk = settings.capital * settings.maxRiskPercent / 100.0
        val stopPercent = when (market.gexSnapshot.regime) {
            GexRegime.NEGATIVE -> 0.03
            else -> 0.02
        }
        val unitRisk = market.quote.lastPrice * stopPercent
        val rawQuantity = floor(maxRisk / unitRisk).toInt().coerceAtLeast(0)
        val approved = reasons.none { it == "Market/GEX snapshot is stale" || it == "No directional fund-manager decision" || it == "Analyst agreement is below the configured minimum" }
        return RiskDecision(
            approved = approved,
            maxRiskAmount = maxRisk,
            allowedQuantity = if (approved) rawQuantity else 0,
            reasons = if (approved) reasons.ifEmpty { listOf("Risk checks passed") } else reasons,
            snapshotId = market.snapshotId,
            inputVersion = market.inputVersion,
        )
    }
}
