package ai.kuber.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Android boundary for the FastAPI contract. No broker credential is represented here. */
interface KuberApi {
    @GET("market/quote/{symbol}") suspend fun quote(@Path("symbol") symbol: String): QuoteDto
    @GET("analysis/gex/{symbol}") suspend fun gex(@Path("symbol") symbol: String): GexSnapshotDto
    @GET("options/chain/{symbol}") suspend fun options(@Path("symbol") symbol: String): List<OptionContractDto>
    @GET("analysis/iv-smile/{symbol}") suspend fun ivSmile(@Path("symbol") symbol: String): IvSmileDto
    @GET("brokers") suspend fun brokers(): List<BrokerStatusDto>
    @GET("portfolio") suspend fun portfolio(): PortfolioDto
    @GET("alerts") suspend fun alerts(): List<AlertDto>
    @GET("analysis/latest/{symbol}") suspend fun latestAnalysis(@Path("symbol") symbol: String): AnalysisResultDto
    @GET("brokers/zerodha/sandbox/login-url") suspend fun zerodhaSandboxLogin(): ZerodhaSandboxLoginDto
    @GET("brokers/zerodha/login-url") suspend fun zerodhaLogin(): BrokerLoginDto
    @POST("brokers/connect") suspend fun connectBroker(@Body request: BrokerConnectRequestDto): BrokerConnectionDto
    @POST("analysis/analyze") suspend fun analyze(@Body request: AnalysisRequestDto): AnalysisResultDto
    @POST("demo/start") suspend fun startDemo(): DemoSessionDto
    @POST("backtest") suspend fun backtest(@Body request: BacktestRequestDto): BacktestResultDto
    @POST("alerts") suspend fun createAlert(@Body request: AlertRequestDto): AlertDto
    @POST("orders/paper") suspend fun paperOrder(@Body request: PaperOrderDto): OrderDto
}
@Serializable
data class QuoteDto(val symbol: String, val last_price: Double, val timestamp: String, val source: String)
@Serializable
data class GexSnapshotDto(
    val snapshot_id: String,
    val symbol: String,
    val spot: Double,
    val gamma_flip: Double?,
    val regime: String,
    val timestamp: String,
    val total_gex: Double = 0.0,
    val gamma_walls: List<Double> = emptyList(),
    val expiry_set: List<String> = emptyList(),
    val source: String = "unknown",
)
@Serializable
data class OptionContractDto(val underlying: String, val strike: Double, val expiry: String, val option_type: String, val open_interest: Int, val implied_volatility: Double, val gamma: Double, val last_price: Double, val lot_size: Int, val volume: Int)
@Serializable
data class IvSmileDto(val symbol: String, val points: List<IvPointDto>)
@Serializable
data class IvPointDto(val strike: Double, val option_type: String, val implied_volatility: Double)
@Serializable
data class BrokerStatusDto(val broker: String, val connected: Boolean, val supports_live_orders: Boolean)
@Serializable
data class PortfolioDto(val holdings: List<JsonObject>, val positions: List<JsonObject>, val funds: Double)
@Serializable
data class AnalysisRequestDto(val symbol: String, val quote: JsonObject, val options: List<JsonObject>)
@Serializable
data class AnalysisResultDto(
    val analysis_id: String,
    val final_bias: String,
    val agents: List<AgentDto>,
    val scorecard: ScorecardDto,
    val debate: DebateDto,
    val trade_plans: List<TradePlanDto>,
    val risk: RiskDto,
)
@Serializable
data class AgentDto(val agent: String, val bias: String, val confidence: Int, val evidence: List<String>, val risks: List<String>)
@Serializable
data class ScorecardDto(val weighted_score: Double, val bias: String, val agreement_percent: Double, val conflicts: List<String>)
@Serializable
data class DebateDto(val bull_argument: String, val bear_argument: String, val facilitator_summary: String)
@Serializable
data class TradePlanDto(val direction: String? = null, val entry: Double? = null, val stop_loss: Double? = null, val targets: List<Double> = emptyList(), val quantity: Int, val risk_profile: String, val rationale: List<String>, val gex_context: String)
@Serializable
data class RiskDto(val approved: Boolean, val max_risk_amount: Double, val quantity: Int, val reasons: List<String>)
@Serializable
data class BacktestRequestDto(val candles: List<JsonObject>, val signals: List<JsonObject>, val initial_capital: Double = 200000.0, val allocation_percent: Double = 25.0, val transaction_cost_bps: Double = 10.0)
@Serializable
data class BacktestResultDto(val initial_capital: Double, val final_equity: Double, val total_return_percent: Double, val max_drawdown_percent: Double, val win_rate_percent: Double, val rejected_signals: Int)
@Serializable
data class AlertRequestDto(val symbol: String, val kind: String, val condition: String)
@Serializable
data class AlertDto(val alert_id: String, val symbol: String, val kind: String, val condition: String, val enabled: Boolean)
@Serializable
data class BrokerConnectRequestDto(val broker: String, val credentials: JsonObject)
@Serializable
data class BrokerConnectionDto(val broker: String, val connection_reference: String, val status: String)
@Serializable
data class ZerodhaSandboxLoginDto(val broker: String, val environment: String, val login_url: String)
@Serializable
data class BrokerLoginDto(val broker: String, val environment: String, val login_url: String)
@Serializable
data class DemoSessionDto(
    val mode: String,
    val symbol: String,
    val source: String,
    val notice: String,
    val quote: QuoteDto,
    val gex: GexSnapshotDto,
    val options: List<OptionContractDto>,
    val analysis: AnalysisResultDto,
)
@Serializable
data class PaperOrderDto(val symbol: String, val side: String, val quantity: Int, val idempotency_key: String, val broker: String = "mock")
@Serializable
data class OrderDto(val order_id: String, val status: String, val broker: String, val mode: String)

object KuberApiFactory {
    fun create(endpoint: String, bearerToken: String? = null): KuberApi {
        val normalized = if (endpoint.endsWith("/")) endpoint else "$endpoint/"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                if (!bearerToken.isNullOrBlank()) header("Authorization", "Bearer $bearerToken")
            }.build()
            chain.proceed(request)
        }.build()
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KuberApi::class.java)
    }
}
