package ai.kuber.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Android boundary for the FastAPI contract. No broker credential is represented here. */
interface KuberApi {
    @GET("market/quote/{symbol}") suspend fun quote(@Path("symbol") symbol: String): QuoteDto
    @GET("analysis/gex/{symbol}") suspend fun gex(@Path("symbol") symbol: String): GexSnapshotDto
    @POST("analysis/analyze") suspend fun analyze(@Body request: AnalysisRequestDto): AnalysisResultDto
    @POST("orders/paper") suspend fun paperOrder(@Body request: PaperOrderDto): OrderDto
}

data class QuoteDto(val symbol: String, val last_price: Double, val timestamp: String, val source: String)
data class GexSnapshotDto(val snapshot_id: String, val symbol: String, val spot: Double, val gamma_flip: Double?, val regime: String, val timestamp: String)
data class AnalysisRequestDto(val symbol: String, val quote: Map<String, Any>, val options: List<Map<String, Any>>)
data class AnalysisResultDto(val analysis_id: String, val final_bias: String, val risk: Map<String, Any>)
data class PaperOrderDto(val symbol: String, val side: String, val quantity: Int, val idempotency_key: String)
data class OrderDto(val order_id: String, val status: String, val broker: String, val mode: String)
