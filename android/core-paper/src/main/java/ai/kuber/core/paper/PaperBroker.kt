package ai.kuber.core.paper

import ai.kuber.core.broker.Broker
import ai.kuber.core.broker.QuoteStream
import ai.kuber.core.broker.QuoteStreamListener
import ai.kuber.core.model.broker.BrokerConnection
import ai.kuber.core.model.broker.BrokerConnectionState
import ai.kuber.core.model.broker.BrokerName
import ai.kuber.core.model.broker.Funds
import ai.kuber.core.model.broker.Holding
import ai.kuber.core.model.broker.OrderReceipt
import ai.kuber.core.model.broker.OrderRequest
import ai.kuber.core.model.broker.Position
import ai.kuber.core.model.broker.TradingMode
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.Quote
import java.util.UUID

data class PaperPortfolio(val funds: Double, val positions: List<Position>, val orders: List<OrderReceipt>)

/** Offline paper execution. A storage adapter can persist this ledger without ever storing broker sessions. */
class PaperBroker(private var cash: Double = 200_000.0) : Broker {
    private val orders = mutableListOf<OrderReceipt>()
    private val positions = linkedMapOf<String, Position>()
    var latestQuote: Quote? = null
    override val connection = BrokerConnection(BrokerName.PAPER, BrokerConnectionState.CONNECTED, "local-paper", "Offline paper broker")
    override fun getQuote(symbol: String): Quote = latestQuote?.takeIf { it.symbol.equals(symbol, true) } ?: throw IllegalStateException("No local quote for $symbol")
    override fun getOptionChain(underlying: String, strikesEachSide: Int): List<OptionContract> = emptyList()
    override fun getPositions(): List<Position> = positions.values.toList()
    override fun getHoldings(): List<Holding> = emptyList()
    override fun getFunds(): Funds = Funds(cash, 0.0, cash)
    override fun placeOrder(request: OrderRequest): OrderReceipt {
        require(request.mode == TradingMode.PAPER && request.broker == BrokerName.PAPER) { "Paper broker accepts paper orders only" }
        val price = request.price ?: getQuote(request.tradingSymbol).lastPrice
        val signed = if (request.side.name == "BUY") request.quantity else -request.quantity
        val existing = positions[request.tradingSymbol]
        val newQuantity = (existing?.quantity ?: 0) + signed
        val newAverage = when {
            newQuantity == 0 -> price
            existing == null || existing.quantity == 0 || existing.quantity.sign() != signed.sign() -> price
            else -> ((existing.averagePrice * kotlin.math.abs(existing.quantity)) + price * kotlin.math.abs(signed)) / kotlin.math.abs(newQuantity)
        }
        cash -= signed * price
        positions[request.tradingSymbol] = Position(request.exchange, request.tradingSymbol, newQuantity, newAverage, price, (price - newAverage) * newQuantity)
        return OrderReceipt("paper-${UUID.randomUUID()}", BrokerName.PAPER, TradingMode.PAPER, "FILLED", request.idempotencyKey, "Paper fill at ${"%.2f".format(price)}").also { orders += it }
    }
    override fun modifyOrder(orderId: String, request: OrderRequest): OrderReceipt = throw UnsupportedOperationException("Paper fills immediately")
    override fun cancelOrder(orderId: String): OrderReceipt = throw UnsupportedOperationException("Paper fills immediately")
    override fun getOrderStatus(orderId: String): String = orders.firstOrNull { it.orderId == orderId }?.status ?: "UNKNOWN"
    override fun streamQuotes(instrumentTokens: List<Long>, listener: QuoteStreamListener): QuoteStream = QuoteStream {}
    fun snapshot(): PaperPortfolio = PaperPortfolio(cash, getPositions(), orders.toList())
}
private fun Int.sign(): Int = when { this > 0 -> 1; this < 0 -> -1; else -> 0 }
