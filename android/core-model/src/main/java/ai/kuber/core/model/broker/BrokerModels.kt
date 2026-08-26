package ai.kuber.core.model.broker

import kotlinx.serialization.Serializable

@Serializable
enum class BrokerName { PAPER, ZERODHA, ANGEL_ONE, FYERS }

@Serializable
enum class BrokerConnectionState { DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE, ERROR }

@Serializable
data class BrokerConnection(
    val broker: BrokerName,
    val state: BrokerConnectionState,
    val accountReference: String? = null,
    val message: String,
)

@Serializable
enum class OrderSide { BUY, SELL }

@Serializable
enum class OrderType { MARKET, LIMIT, SL, SLM }

@Serializable
enum class TradingMode { PAPER, LIVE }

@Serializable
data class OrderRequest(
    val broker: BrokerName,
    val mode: TradingMode,
    val exchange: String,
    val tradingSymbol: String,
    val side: OrderSide,
    val quantity: Int,
    val orderType: OrderType,
    val product: String,
    val price: Double? = null,
    val triggerPrice: Double? = null,
    val idempotencyKey: String,
    val snapshotId: String,
    val inputVersion: String,
) {
    init {
        require(exchange.isNotBlank() && tradingSymbol.isNotBlank())
        require(quantity > 0)
        require(idempotencyKey.length >= 8)
        require(snapshotId.isNotBlank() && inputVersion.isNotBlank())
        if (orderType == OrderType.LIMIT) require(price != null && price > 0)
        if (orderType == OrderType.SL || orderType == OrderType.SLM) require(triggerPrice != null && triggerPrice > 0)
    }
}

@Serializable
data class OrderReceipt(
    val orderId: String,
    val broker: BrokerName,
    val mode: TradingMode,
    val status: String,
    val idempotencyKey: String,
    val message: String,
)

@Serializable
data class Position(
    val exchange: String,
    val tradingSymbol: String,
    val quantity: Int,
    val averagePrice: Double,
    val lastPrice: Double,
    val pnl: Double,
)

@Serializable
data class Holding(
    val exchange: String,
    val tradingSymbol: String,
    val quantity: Int,
    val averagePrice: Double,
    val lastPrice: Double,
    val pnl: Double,
)

@Serializable
data class Funds(val availableCash: Double, val usedMargin: Double, val totalBalance: Double)
