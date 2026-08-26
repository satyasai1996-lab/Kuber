package ai.kuber.core.broker

import ai.kuber.core.model.broker.BrokerConnection
import ai.kuber.core.model.broker.Funds
import ai.kuber.core.model.broker.Holding
import ai.kuber.core.model.broker.OrderReceipt
import ai.kuber.core.model.broker.OrderRequest
import ai.kuber.core.model.broker.Position
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.Quote

/** Common provider contract. No UI class calls a provider implementation directly. */
interface Broker {
    val connection: BrokerConnection
    fun getQuote(symbol: String): Quote
    fun getOptionChain(underlying: String, strikesEachSide: Int = 10): List<OptionContract>
    fun getPositions(): List<Position>
    fun getHoldings(): List<Holding>
    fun getFunds(): Funds
    fun placeOrder(request: OrderRequest): OrderReceipt
    fun modifyOrder(orderId: String, request: OrderRequest): OrderReceipt
    fun cancelOrder(orderId: String): OrderReceipt
    fun getOrderStatus(orderId: String): String
    fun streamQuotes(instrumentTokens: List<Long>, listener: QuoteStreamListener): QuoteStream
}

interface QuoteStreamListener {
    fun onConnected()
    fun onQuote(instrumentToken: Long, lastPrice: Double, receivedAt: Long)
    fun onReconnecting(attempt: Int)
    fun onError(message: String)
}

fun interface QuoteStream { fun close() }
