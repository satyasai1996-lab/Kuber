package ai.kuber.app

import ai.kuber.core.agents.AnalysisContext
import ai.kuber.core.agents.AnalysisOrchestrator
import ai.kuber.core.broker.zerodha.OkHttpTransport
import ai.kuber.core.broker.zerodha.ZerodhaBroker
import ai.kuber.core.execution.ExecutionCoordinator
import ai.kuber.core.execution.LiveConfirmation
import ai.kuber.core.market.intelligence.MarketIntelligenceBuilder
import ai.kuber.core.market.options.OptionsAnalyticsCalculator
import ai.kuber.core.model.analysis.AnalysisResult
import ai.kuber.core.model.analysis.RiskProfile
import ai.kuber.core.model.broker.BrokerConnection
import ai.kuber.core.model.broker.BrokerName
import ai.kuber.core.model.broker.OrderRequest
import ai.kuber.core.model.broker.OrderSide
import ai.kuber.core.model.broker.OrderType
import ai.kuber.core.model.broker.TradingMode
import ai.kuber.core.model.market.MarketIntelligence
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.OptionsAnalytics
import ai.kuber.core.model.market.Quote
import ai.kuber.core.paper.PaperBroker
import java.util.UUID

data class LocalKuberState(
    val connection: BrokerConnection,
    val market: MarketIntelligence? = null,
    val options: OptionsAnalytics? = null,
    val analysis: AnalysisResult? = null,
    val status: String = "Use Broker to connect Zerodha, or load the clearly labelled local paper demo.",
    val liveData: Boolean = false,
    val selectedInstrument: TradeInstrument = TradeInstrument.NIFTY,
)

data class LocalBacktestResult(
    val instrument: TradeInstrument,
    val candles: Int,
    val trades: Int,
    val wins: Int,
    val netReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val status: String,
)

/** In-process Kuber service layer. It replaces the previous FastAPI/Retrofit path. */
class LocalTradingRuntime {
    private val zerodha = ZerodhaBroker(OkHttpTransport())
    private val paper = PaperBroker()
    private val intelligence = MarketIntelligenceBuilder()
    private val analytics = OptionsAnalyticsCalculator()
    private val orchestrator = AnalysisOrchestrator()
    private val execution = ExecutionCoordinator()

    var state = LocalKuberState(zerodha.connection)
        private set

    fun loginUrl(apiKey: String): String = zerodha.auth.loginUrl(apiKey)
    fun connectZerodha(apiKey: String, apiSecret: CharArray, requestToken: String) {
        val connection = zerodha.auth.exchangeRequestToken(apiKey, apiSecret, requestToken)
        state = state.copy(connection = connection, status = "Zerodha session connected on this phone. Refresh NIFTY to load live broker data.")
    }
    fun logout() { zerodha.logout(); state = state.copy(connection = zerodha.connection, status = "Zerodha session cleared from memory.") }
    fun setStatus(message: String) { state = state.copy(status = message) }

    fun selectInstrument(instrument: TradeInstrument) {
        state = state.copy(selectedInstrument = instrument, market = null, options = null, analysis = null,
            liveData = false, status = "${instrument.name} selected. Load its local paper fixture or refresh an authenticated broker snapshot.")
    }

    fun loadPaperDemo() {
        val instrument = state.selectedInstrument
        val now = System.currentTimeMillis()
        val quote = Quote(instrument.symbol, instrument.demoSpot, now, "local_paper_fixture", volume = 1_500_000, vwap = instrument.demoSpot * .998)
        val chain = listOf(-4, -2, 0, 2, 4).flatMap { offset ->
            val strike = instrument.demoSpot + offset * instrument.strikeStep
            val distance = kotlin.math.abs(offset)
            listOf(
                option(instrument, strike, OptionType.CE, 105_000L + (4 - distance) * 18_000L, .135 + distance * .004, .009 + (4 - distance) * .003, 105.0 + distance * 44, now),
                option(instrument, strike, OptionType.PE, 112_000L + (4 - distance) * 16_000L, .142 + distance * .004, .010 + (4 - distance) * .003, 101.0 + distance * 42, now),
            )
        }
        publish(quote, chain, isLive = false, status = "${instrument.name} local paper fixture loaded. Values are deterministic demo data, not live market data.")
    }

    fun refreshZerodha(symbol: String = state.selectedInstrument.name) {
        require(state.selectedInstrument.directZerodhaSupported) { "${state.selectedInstrument.symbol} requires the authenticated backend market adapter; direct refresh is unavailable." }
        check(zerodha.connection.state.name == "CONNECTED") { "Connect Zerodha first" }
        val quote = zerodha.getQuote(symbol)
        val chain = zerodha.getOptionChain(symbol)
        check(chain.isNotEmpty()) { "Kite returned no valid option contracts" }
        publish(quote, chain, isLive = true, status = "Live Zerodha snapshot refreshed directly on this phone.")
    }

    fun submitPaper(profile: RiskProfile) {
        val current = requireNotNull(state.market) { "Load a market snapshot first" }
        val analysis = requireNotNull(state.analysis) { "Run analysis first" }
        val plan = analysis.tradePlans.first { it.profile == profile }
        require(plan.quantity > 0 && plan.direction.name in setOf("BULLISH", "BEARISH")) { "Final Risk Manager did not approve this plan" }
        paper.latestQuote = current.quote
        val request = OrderRequest(BrokerName.PAPER, TradingMode.PAPER, "NSE", current.quote.symbol, if (plan.direction.name == "BULLISH") OrderSide.BUY else OrderSide.SELL, plan.quantity, OrderType.MARKET, "MIS", idempotencyKey = UUID.randomUUID().toString(), snapshotId = current.snapshotId, inputVersion = current.inputVersion)
        val receipt = execution.submitPaper(paper, request, current, analysis.finalRisk)
        state = state.copy(status = "Paper order ${receipt.status}: ${receipt.orderId}")
    }

    /** Returns a review hash for a user-specified, broker-valid instrument. It does not place an order. */
    fun previewLive(profile: RiskProfile, exchange: String, tradingSymbol: String, quantity: Int, requestId: String): String {
        val (request, _, _) = liveRequest(profile, exchange, tradingSymbol, quantity, requestId)
        return execution.previewHash(request)
    }

    /** The UI must show the returned hash and require the literal acknowledgement LIVE before this call. */
    fun submitLive(profile: RiskProfile, exchange: String, tradingSymbol: String, quantity: Int, requestId: String, reviewedHash: String, acknowledgement: String) {
        val (request, market, risk) = liveRequest(profile, exchange, tradingSymbol, quantity, requestId)
        val receipt = execution.confirmLive(zerodha, request, market, risk, LiveConfirmation(reviewedHash, acknowledgement))
        state = state.copy(status = "Live order ${receipt.status}: ${receipt.orderId}. Check Zerodha order status for execution.")
    }

    fun paperPortfolio() = paper.snapshot()
    fun auditEvents() = execution.auditEvents()

    /** A deterministic bar-by-bar moving-average simulation. Each signal uses only closed bars available at that index. */
    fun runPaperBacktest(): LocalBacktestResult {
        val instrument = state.selectedInstrument
        val base = instrument.demoSpot
        val changes = listOf(-.004, -.002, .003, .006, .004, -.003, .005, .008, -.006, -.005, .002, .006, .004, -.007, .003, .006, .002, -.004, .005, .003)
        val prices = changes.runningFold(base) { price, change -> price * (1 + change) }
        var cash = 100_000.0; var peak = cash; var maxDrawdown = 0.0; var trades = 0; var wins = 0; var entry: Double? = null
        for (index in 5 until prices.size) {
            val shortAverage = prices.subList(index - 3, index).average()
            val longAverage = prices.subList(index - 5, index).average()
            val close = prices[index]
            if (entry == null && shortAverage > longAverage) { entry = close; trades++ }
            else if (entry != null && (shortAverage < longAverage || index == prices.lastIndex)) {
                val result = (close - entry) / entry
                cash *= (1 + result); if (result > 0) wins++; entry = null
            }
            peak = maxOf(peak, cash); maxDrawdown = maxOf(maxDrawdown, (peak - cash) / peak)
        }
        val result = LocalBacktestResult(instrument, prices.size, trades, wins, (cash / 100_000.0 - 1) * 100, maxDrawdown * 100,
            "Completed against local historical fixture. No future candle is read when deciding an entry or exit.")
        state = state.copy(status = "${instrument.name} no-lookahead paper backtest completed: ${"%.2f".format(result.netReturnPercent)}%.")
        return result
    }

    private fun liveRequest(profile: RiskProfile, exchange: String, tradingSymbol: String, quantity: Int, requestId: String): Triple<OrderRequest, MarketIntelligence, ai.kuber.core.model.analysis.RiskDecision> {
        require(state.liveData) { "Refresh a direct Zerodha snapshot before a live order" }
        val market = requireNotNull(state.market) { "No market snapshot" }
        val analysis = requireNotNull(state.analysis) { "No bot analysis" }
        val plan = analysis.tradePlans.first { it.profile == profile }
        require(plan.quantity > 0 && plan.direction.name in setOf("BULLISH", "BEARISH")) { "Final Risk Manager did not approve this plan" }
        require(exchange in setOf("NFO", "NSE", "BSE", "BFO", "MCX")) { "Unsupported exchange" }
        require(tradingSymbol.trim().matches(Regex("[A-Za-z0-9]+"))) { "Enter a valid broker trading symbol" }
        require(requestId.length >= 8) { "Invalid order review ID" }
        val request = OrderRequest(BrokerName.ZERODHA, TradingMode.LIVE, exchange, tradingSymbol.trim().uppercase(), if (plan.direction.name == "BULLISH") OrderSide.BUY else OrderSide.SELL, quantity, OrderType.MARKET, "MIS", idempotencyKey = requestId, snapshotId = market.snapshotId, inputVersion = market.inputVersion)
        return Triple(request, market, analysis.finalRisk)
    }

    private fun publish(quote: Quote, chain: List<OptionContract>, isLive: Boolean, status: String) {
        val market = intelligence.build(quote, chain)
        val options = analytics.calculate(market)
        val analysis = orchestrator.analyze(AnalysisContext(market, options, fundamentalScore = if (isLive) null else 15.0, newsMacroScore = if (isLive) null else 8.0, sentimentScore = if (isLive) null else 10.0, sectorRotationScore = if (isLive) null else 6.0))
        paper.latestQuote = market.quote
        state = LocalKuberState(zerodha.connection, market, options, analysis, status, isLive, state.selectedInstrument)
    }
    private fun option(instrument: TradeInstrument, strike: Double, type: OptionType, oi: Long, iv: Double, gamma: Double, price: Double, now: Long) =
        OptionContract(instrument.symbol, strike, "2026-08-27", type, oi, iv, gamma, price, instrument.lotSize, now, "local_paper_fixture", volume = 50_000)
}
