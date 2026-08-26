package ai.kuber.core.broker.zerodha

import ai.kuber.core.broker.Broker
import ai.kuber.core.broker.QuoteStream
import ai.kuber.core.broker.QuoteStreamListener
import ai.kuber.core.market.options.BlackScholes
import ai.kuber.core.model.broker.BrokerConnection
import ai.kuber.core.model.broker.BrokerConnectionState
import ai.kuber.core.model.broker.BrokerName
import ai.kuber.core.model.broker.Funds
import ai.kuber.core.model.broker.Holding
import ai.kuber.core.model.broker.OrderReceipt
import ai.kuber.core.model.broker.OrderRequest
import ai.kuber.core.model.broker.OrderSide
import ai.kuber.core.model.broker.Position
import ai.kuber.core.model.market.OptionContract
import ai.kuber.core.model.market.OptionType
import ai.kuber.core.model.market.Quote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TransportResponse(val code: Int, val body: String)
fun interface HttpTransport { fun execute(method: String, url: String, headers: Map<String, String>, body: String?): TransportResponse }

/** Production HTTPS transport used by the Android runtime; tests inject HttpTransport fakes. */
class OkHttpTransport(private val client: OkHttpClient = OkHttpClient()) : HttpTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): TransportResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            method(method, body?.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }.build()
        client.newCall(request).execute().use { response -> return TransportResponse(response.code, response.body?.string().orEmpty()) }
    }
}

/** Volatile-only session. It deliberately has no Android storage dependency. */
class EphemeralZerodhaSession {
    @Volatile private var apiKey: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var userId: String? = null
    fun connect(apiKey: String, accessToken: String, userId: String) { this.apiKey = apiKey; this.accessToken = accessToken; this.userId = userId }
    fun credentials(): Pair<String, String> = (apiKey ?: throw IllegalStateException("Zerodha is not connected")) to (accessToken ?: throw IllegalStateException("Zerodha is not connected"))
    fun userId(): String? = userId
    fun clear() { apiKey = null; accessToken = null; userId = null }
    fun connected(): Boolean = apiKey != null && accessToken != null
}

class ZerodhaAuth(private val transport: HttpTransport, private val session: EphemeralZerodhaSession) {
    fun loginUrl(apiKey: String): String {
        require(apiKey.isNotBlank()) { "Kite API key is required" }
        return "https://kite.zerodha.com/connect/login?v=3&api_key=${encoded(apiKey)}"
    }

    /** Personal mobile mode: secret is accepted only as a session char array and erased after exchange. */
    fun exchangeRequestToken(apiKey: String, apiSecret: CharArray, requestToken: String): BrokerConnection {
        require(apiKey.isNotBlank() && apiSecret.isNotEmpty() && requestToken.isNotBlank()) { "Kite API key, secret and request token are required" }
        try {
            val secret = apiSecret.concatToString()
            val checksum = sha256(apiKey + requestToken + secret)
            val response = transport.execute(
                "POST", "https://api.kite.trade/session/token", mapOf("X-Kite-Version" to "3", "Accept" to "application/json"),
                form(mapOf("api_key" to apiKey, "request_token" to requestToken, "checksum" to checksum)),
            )
            val data = dataOrThrow(response)
            val token = data.string("access_token") ?: throw IllegalStateException("Kite did not return an access token")
            val user = data.string("user_id") ?: "connected"
            session.connect(apiKey, token, user)
            return BrokerConnection(BrokerName.ZERODHA, BrokerConnectionState.CONNECTED, "kite:$user", "Zerodha connected for this app session")
        } finally { apiSecret.fill('\u0000') }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class ZerodhaInstrument(val token: Long, val tradingSymbol: String, val name: String, val expiry: String, val strike: Double, val lotSize: Int, val type: OptionType, val exchange: String)

/** Direct phone-to-Kite REST adapter. It never calls the Kuber FastAPI service. */
class ZerodhaBroker(
    private val transport: HttpTransport,
    private val session: EphemeralZerodhaSession = EphemeralZerodhaSession(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Broker {
    val auth = ZerodhaAuth(transport, session)
    @Volatile private var instruments: List<ZerodhaInstrument>? = null
    override val connection: BrokerConnection
        get() = if (session.connected()) BrokerConnection(BrokerName.ZERODHA, BrokerConnectionState.CONNECTED, session.userId()?.let { "kite:$it" }, "Session-only connection") else BrokerConnection(BrokerName.ZERODHA, BrokerConnectionState.DISCONNECTED, null, "Enter Kite details to connect")

    fun logout() = session.clear()

    override fun getQuote(symbol: String): Quote {
        val normalized = symbol.trim().uppercase()
        val instrument = when (normalized) { "NIFTY" -> "NSE:NIFTY 50"; "BANKNIFTY" -> "NSE:NIFTY BANK"; else -> if (normalized.contains(':')) normalized else "NSE:$normalized" }
        val item = fetchQuotes(listOf(instrument))[instrument] ?: throw IllegalStateException("Kite returned no quote for $symbol")
        val ohlc = item["ohlc"]?.jsonObject
        return Quote(normalized, item.number("last_price") ?: throw IllegalStateException("Kite quote has no last price"), clock(), "zerodha", item.long("volume"), item.number("average_price"))
    }

    override fun getOptionChain(underlying: String, strikesEachSide: Int): List<OptionContract> {
        require(strikesEachSide in 1..30)
        val symbol = underlying.trim().uppercase()
        val spot = getQuote(symbol)
        val today = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), ZoneId.of("Asia/Kolkata")).toLocalDate()
        val candidates = loadNfoInstruments().filter { it.name.equals(symbol, true) && it.expiry.isNotBlank() }
        val expiry = candidates.mapNotNull { runCatching { LocalDate.parse(it.expiry) }.getOrNull() }.filter { !it.isBefore(today) }.minOrNull() ?: throw IllegalStateException("No current $symbol option expiry returned by Kite")
        val expiryCandidates = candidates.filter { it.expiry == expiry.toString() }
        val strikes = expiryCandidates.map { it.strike }.distinct().sorted()
        val nearest = strikes.minByOrNull { kotlin.math.abs(it - spot.lastPrice) } ?: throw IllegalStateException("No $symbol strikes returned by Kite")
        val nearestIndex = strikes.indexOf(nearest)
        val selectedStrikes = strikes.subList((nearestIndex - strikesEachSide).coerceAtLeast(0), (nearestIndex + strikesEachSide + 1).coerceAtMost(strikes.size)).toSet()
        val selected = expiryCandidates.filter { it.strike in selectedStrikes }
        val quotes = fetchQuotes(selected.map { "NFO:${it.tradingSymbol}" })
        val timeYears = timeToExpiry(expiry)
        return selected.mapNotNull { instrument ->
            val raw = quotes["NFO:${instrument.tradingSymbol}"] ?: return@mapNotNull null
            val price = raw.number("last_price") ?: return@mapNotNull null
            if (price <= 0.0) return@mapNotNull null
            val greeks = runCatching { BlackScholes.calculateGreeks(instrument.type, price, spot.lastPrice, instrument.strike, timeYears, .065) }.getOrNull() ?: return@mapNotNull null
            OptionContract(symbol, instrument.strike, instrument.expiry, instrument.type, raw.long("oi") ?: 0L, greeks.impliedVolatility, greeks.gamma, price, instrument.lotSize, clock(), "zerodha", raw.long("volume") ?: 0L, raw.long("oi_day_change"))
        }.sortedWith(compareBy<OptionContract> { it.strike }.thenBy { it.optionType.name })
    }

    override fun getPositions(): List<Position> = arrayData("/portfolio/positions", "net").map { item -> Position(item.string("exchange") ?: "", item.string("tradingsymbol") ?: "", item.long("quantity")?.toInt() ?: 0, item.number("average_price") ?: 0.0, item.number("last_price") ?: 0.0, item.number("pnl") ?: 0.0) }
    override fun getHoldings(): List<Holding> = arrayData("/portfolio/holdings").map { item -> Holding(item.string("exchange") ?: "", item.string("tradingsymbol") ?: "", item.long("quantity")?.toInt() ?: 0, item.number("average_price") ?: 0.0, item.number("last_price") ?: 0.0, item.number("pnl") ?: 0.0) }
    override fun getFunds(): Funds {
        val equity = dataOrThrow(call("GET", "https://api.kite.trade/user/margins"))["equity"]?.jsonObject ?: return Funds(0.0, 0.0, 0.0)
        val available = equity["available"]?.jsonObject
        val utilised = equity["utilised"]?.jsonObject
        return Funds(available?.number("live_balance") ?: available?.number("cash") ?: 0.0, utilised?.number("debits") ?: 0.0, equity.number("net") ?: 0.0)
    }

    override fun placeOrder(request: OrderRequest): OrderReceipt {
        require(request.broker == BrokerName.ZERODHA) { "Wrong broker for Zerodha adapter" }
        val response = dataOrThrow(call("POST", "https://api.kite.trade/orders/regular", form(orderFields(request))))
        return OrderReceipt(response.string("order_id") ?: throw IllegalStateException("Kite did not return order ID"), BrokerName.ZERODHA, request.mode, "SUBMITTED", request.idempotencyKey, "Kite accepted the order request; retrieve status separately")
    }
    override fun modifyOrder(orderId: String, request: OrderRequest): OrderReceipt { val response = dataOrThrow(call("PUT", "https://api.kite.trade/orders/regular/${encoded(orderId)}", form(orderFields(request)))); return OrderReceipt(response.string("order_id") ?: orderId, BrokerName.ZERODHA, request.mode, "MODIFIED", request.idempotencyKey, "Kite accepted order modification") }
    override fun cancelOrder(orderId: String): OrderReceipt { val response = dataOrThrow(call("DELETE", "https://api.kite.trade/orders/regular/${encoded(orderId)}")); return OrderReceipt(response.string("order_id") ?: orderId, BrokerName.ZERODHA, ai.kuber.core.model.broker.TradingMode.LIVE, "CANCELLED", "broker-$orderId", "Kite accepted cancellation") }
    override fun getOrderStatus(orderId: String): String = dataOrThrow(call("GET", "https://api.kite.trade/orders/${encoded(orderId)}")).let { it["status"]?.jsonPrimitive?.content ?: "UNKNOWN" }

    /** HTTPS polling fallback with reconnect notifications; binary Kite WebSocket is a separate release gate. */
    override fun streamQuotes(instrumentTokens: List<Long>, listener: QuoteStreamListener): QuoteStream {
        val known = loadNfoInstruments().filter { it.token in instrumentTokens }.associateBy { it.token }
        if (known.isEmpty()) { listener.onError("No requested option tokens are present in the cached NFO instrument master"); return QuoteStream {} }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        listener.onConnected()
        var failures = 0
        scheduler.scheduleWithFixedDelay({
            try {
                val raw = fetchQuotes(known.values.map { "NFO:${it.tradingSymbol}" })
                known.forEach { (token, instrument) -> raw["NFO:${instrument.tradingSymbol}"]?.number("last_price")?.let { listener.onQuote(token, it, clock()) } }
                failures = 0
            } catch (error: Exception) { failures += 1; listener.onReconnecting(failures); listener.onError("Quote refresh failed: ${error.javaClass.simpleName}") }
        }, 0, 5, TimeUnit.SECONDS)
        return QuoteStream { scheduler.shutdownNow() }
    }

    private fun loadNfoInstruments(): List<ZerodhaInstrument> {
        instruments?.let { return it }
        val response = call("GET", "https://api.kite.trade/instruments/NFO")
        if (response.code !in 200..299) throw IllegalStateException("Kite instrument request failed (HTTP ${response.code})")
        return parseCsv(response.body).also { instruments = it }
    }
    private fun fetchQuotes(instruments: List<String>): Map<String, JsonObject> {
        val result = linkedMapOf<String, JsonObject>()
        instruments.chunked(500).forEach { batch ->
            val url = "https://api.kite.trade/quote?" + batch.joinToString("&") { "i=${encoded(it)}" }
            val data = dataOrThrow(call("GET", url))
            data.forEach { (key, value) -> result[key] = value.jsonObject }
        }
        return result
    }
    private fun arrayData(path: String, nested: String? = null): List<JsonObject> {
        val data = dataElementOrThrow(call("GET", "https://api.kite.trade$path"))
        val value = nested?.let { data.jsonObject[it] } ?: data
        return when (value) { is JsonArray -> value.map { it.jsonObject }; is JsonObject -> value.values.firstOrNull { it is JsonArray }?.jsonArray?.map { it.jsonObject } ?: emptyList(); else -> emptyList() }
    }
    private fun call(method: String, url: String, body: String? = null): TransportResponse { val (key, token) = session.credentials(); return transport.execute(method, url, mapOf("X-Kite-Version" to "3", "Authorization" to "token $key:$token", "Accept" to "application/json"), body) }
    private fun orderFields(request: OrderRequest): Map<String, String> = buildMap { put("tradingsymbol", request.tradingSymbol); put("exchange", request.exchange); put("transaction_type", request.side.name); put("quantity", request.quantity.toString()); put("order_type", request.orderType.name.replace("SLM", "SL-M")); put("product", request.product); put("validity", "DAY"); put("tag", "kuber${request.idempotencyKey.take(14)}"); request.price?.let { put("price", it.toString()) }; request.triggerPrice?.let { put("trigger_price", it.toString()) } }
    private fun timeToExpiry(expiry: LocalDate): Double = ((LocalDateTime.of(expiry, LocalTime.of(15, 30)).atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli() - clock()).coerceAtLeast(60_000L)).toDouble() / (365.0 * 24 * 60 * 60 * 1000)
}

private val kiteJson = Json { ignoreUnknownKeys = true }
private fun dataElementOrThrow(response: TransportResponse) = run {
    val root = runCatching { kiteJson.parseToJsonElement(response.body).jsonObject }.getOrElse { throw IllegalStateException("Kite returned unreadable data (HTTP ${response.code})") }
    if (response.code !in 200..299 || root["status"]?.jsonPrimitive?.content != "success") throw IllegalStateException(root["message"]?.jsonPrimitive?.content ?: "Kite request failed (HTTP ${response.code})")
    root["data"] ?: throw IllegalStateException("Kite response has no data")
}
private fun dataOrThrow(response: TransportResponse): JsonObject = dataElementOrThrow(response).jsonObject
private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
private fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
private fun form(values: Map<String, String>): String = values.entries.joinToString("&") { "${encoded(it.key)}=${encoded(it.value)}" }
private fun parseCsv(raw: String): List<ZerodhaInstrument> {
    val lines = raw.lineSequence().filter { it.isNotBlank() }.toList(); if (lines.size < 2) return emptyList()
    val headers = csvRow(lines.first()).mapIndexed { index, value -> value to index }.toMap()
    fun cell(row: List<String>, name: String) = headers[name]?.let { row.getOrNull(it).orEmpty() }.orEmpty()
    return lines.drop(1).mapNotNull { line ->
        val row = csvRow(line); val kind = when (cell(row, "instrument_type")) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> return@mapNotNull null }
        val expiry = cell(row, "expiry"); val strike = cell(row, "strike").toDoubleOrNull() ?: return@mapNotNull null
        ZerodhaInstrument(cell(row, "instrument_token").toLongOrNull() ?: return@mapNotNull null, cell(row, "tradingsymbol"), cell(row, "name"), expiry, strike, cell(row, "lot_size").toIntOrNull() ?: return@mapNotNull null, kind, cell(row, "exchange"))
    }
}
private fun csvRow(line: String): List<String> { val out=mutableListOf<String>(); val current=StringBuilder(); var quote=false; var index=0; while(index<line.length){ val c=line[index]; if(c=='\"'){ if(quote && index+1<line.length && line[index+1]=='\"'){ current.append(c); index++ } else quote=!quote } else if(c==',' && !quote){ out+=current.toString(); current.clear() } else current.append(c); index++ }; out+=current.toString(); return out }
