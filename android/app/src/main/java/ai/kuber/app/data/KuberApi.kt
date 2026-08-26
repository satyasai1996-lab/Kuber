package ai.kuber.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Android boundary for the FastAPI contract. No broker credential is represented here. */
interface KuberApi {
    @GET("market/quote/{symbol}") suspend fun quote(@Path("symbol") symbol: String): QuoteDto
    @GET("analysis/gex/{symbol}") suspend fun gex(@Path("symbol") symbol: String): GexSnapshotDto
    @GET("options/chain/{symbol}") suspend fun options(@Path("symbol") symbol: String): List<OptionContractDto>
    @GET("analysis/iv-smile/{symbol}") suspend fun ivSmile(@Path("symbol") symbol: String): IvSmileDto
    @GET("brokers") suspend fun brokers(): List<BrokerStatusDto>
    @GET("portfolio") suspend fun portfolio(): PortfolioDto
    @GET("alerts") suspend fun alerts(): List<AlertDto>
    @POST("analysis/analyze") suspend fun analyze(@Body request: AnalysisRequestDto): AnalysisResultDto
    @POST("backtest") suspend fun backtest(@Body request: BacktestRequestDto): BacktestResultDto
    @POST("alerts") suspend fun createAlert(@Body request: AlertRequestDto): AlertDto
    @POST("orders/paper") suspend fun paperOrder(@Body request: PaperOrderDto): OrderDto
}

@Serializable
data class QuoteDto(val symbol: String, val last_price: Double, val timestamp: String, val source: String)
@Serializable
data class GexSnapshotDto(val snapshot_id: String, val symbol: String, val spot: Double, val gamma_flip: Double?, val regime: String, val timestamp: String)
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
data class AnalysisResultDto(val analysis_id: String, val final_bias: String, val risk: JsonObject)
@Serializable
data class BacktestRequestDto(val candles: List<JsonObject>, val signals: List<JsonObject>, val initial_capital: Double = 200000.0, val allocation_percent: Double = 25.0, val transaction_cost_bps: Double = 10.0)
@Serializable
data class BacktestResultDto(val initial_capital: Double, val final_equity: Double, val total_return_percent: Double, val max_drawdown_percent: Double, val win_rate_percent: Double, val rejected_signals: Int)
@Serializable
data class AlertRequestDto(val symbol: String, val kind: String, val condition: String)
@Serializable
data class AlertDto(val alert_id: String, val symbol: String, val kind: String, val condition: String, val enabled: Boolean)
@Serializable
data class PaperOrderDto(val symbol: String, val side: String, val quantity: Int, val idempotency_key: String)
@Serializable
data class OrderDto(val order_id: String, val status: String, val broker: String, val mode: String)
