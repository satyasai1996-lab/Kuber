package ai.kuber.core.model.analysis

import kotlinx.serialization.Serializable

@Serializable
enum class Bias { BULLISH, BEARISH, NEUTRAL, UNAVAILABLE }

@Serializable
enum class AnalystName {
    TECHNICAL,
    FUNDAMENTAL,
    OPTIONS,
    NEWS_MACRO,
    SENTIMENT,
    SECTOR_ROTATION,
    RISK_MANAGER,
}

@Serializable
enum class GexAlignment { SUPPORTS, OPPOSES, NEUTRAL, UNAVAILABLE }

@Serializable
data class AnalystResult(
    val analyst: AnalystName,
    val bias: Bias,
    val confidence: Int,
    val score: Double,
    val evidence: List<String>,
    val risks: List<String>,
    val snapshotId: String,
    val inputVersion: String,
    val inputCapturedAt: Long,
) {
    init {
        require(confidence in 0..100) { "confidence must be 0..100" }
        require(score.isFinite() && score in -100.0..100.0) { "score must be -100..100" }
        require(snapshotId.isNotBlank() && inputVersion.isNotBlank()) { "snapshot identity is required" }
        if (bias == Bias.UNAVAILABLE) require(score == 0.0) { "unavailable analyst cannot score" }
    }
}

@Serializable
data class Scorecard(
    val weightedScore: Double,
    val bias: Bias,
    val agreementPercent: Double,
    val activeAnalysts: Int,
)

@Serializable
data class ConflictRecord(
    val bullishAnalysts: List<AnalystName>,
    val bearishAnalysts: List<AnalystName>,
    val message: String,
)

@Serializable
data class Debate(
    val bullArgument: String,
    val bearArgument: String,
    val bullRebuttal: String,
    val bearRebuttal: String,
    val facilitatorSummary: String,
)

@Serializable
data class FundManagerDecision(
    val bias: Bias,
    val confidence: Int,
    val gexAlignment: GexAlignment,
    val rationale: List<String>,
)

@Serializable
data class RiskDecision(
    val approved: Boolean,
    val maxRiskAmount: Double,
    val allowedQuantity: Int,
    val reasons: List<String>,
    val snapshotId: String,
    val inputVersion: String,
)

@Serializable
enum class RiskProfile { AGGRESSIVE, NEUTRAL, CONSERVATIVE }

@Serializable
data class TradePlan(
    val profile: RiskProfile,
    val direction: Bias,
    val entry: Double?,
    val stopLoss: Double?,
    val targets: List<Double>,
    val quantity: Int,
    val rewardRisk: Double?,
    val gexContext: GexAlignment,
    val rationale: List<String>,
    val snapshotId: String,
    val inputVersion: String,
)

@Serializable
enum class PipelineStage {
    SNAPSHOT,
    SEVEN_ANALYSTS,
    SCHEMA_VALIDATION,
    SCORECARD_AND_CONFLICTS,
    BULL_CASE,
    BEAR_CASE,
    REBUTTALS,
    FACILITATOR,
    FUND_MANAGER,
    FINAL_RISK_MANAGER,
    THREE_TRADE_PLANS,
    EXECUTION_GATE,
}

@Serializable
data class AnalysisResult(
    val analysisId: String,
    val snapshotId: String,
    val inputVersion: String,
    val stages: List<PipelineStage>,
    val analysts: List<AnalystResult>,
    val scorecard: Scorecard,
    val conflicts: List<ConflictRecord>,
    val debate: Debate,
    val fundManager: FundManagerDecision,
    val finalRisk: RiskDecision,
    val tradePlans: List<TradePlan>,
    val finalBias: Bias,
) {
    init {
        require(analysts.size == AnalystName.entries.size) { "exactly seven analysts are required" }
        require(analysts.map(AnalystResult::analyst).toSet() == AnalystName.entries.toSet()) {
            "each analyst must appear exactly once"
        }
        require(analysts.all { it.snapshotId == snapshotId && it.inputVersion == inputVersion }) {
            "all analyst results must share the market snapshot"
        }
        require(finalRisk.snapshotId == snapshotId && finalRisk.inputVersion == inputVersion) {
            "final risk decision must share the market snapshot"
        }
        require(tradePlans.size == RiskProfile.entries.size) { "exactly three trade plans are required" }
        require(tradePlans.all { it.snapshotId == snapshotId && it.inputVersion == inputVersion }) {
            "trade plans must share the market snapshot"
        }
    }
}
