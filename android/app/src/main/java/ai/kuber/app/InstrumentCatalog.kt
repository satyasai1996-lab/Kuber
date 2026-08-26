package ai.kuber.app

/** Searchable market universe. Values here identify instruments; they are not market prices. */
enum class TradeInstrument(
    val symbol: String,
    val displayName: String,
    val cashExchange: String,
    val derivativesExchange: String,
    val category: String,
    val aliases: Set<String>,
    val demoSpot: Double,
    val strikeStep: Double,
    val lotSize: Int,
    val directZerodhaSupported: Boolean = false,
) {
    NIFTY("NIFTY", "NIFTY 50", "NSE", "NFO", "Index", setOf("NIFTY50", "NSE INDEX"), 22_000.0, 50.0, 25, true),
    BANKNIFTY("BANKNIFTY", "NIFTY BANK", "NSE", "NFO", "Index", setOf("BANK NIFTY", "NIFTYBANK"), 48_500.0, 100.0, 15, true),
    FINNIFTY("FINNIFTY", "NIFTY FIN SERVICE", "NSE", "NFO", "Index", setOf("FIN NIFTY", "FINANCIAL SERVICES"), 23_500.0, 50.0, 25),
    MIDCPNIFTY("MIDCPNIFTY", "NIFTY MIDCAP SELECT", "NSE", "NFO", "Index", setOf("MIDCAP NIFTY"), 12_000.0, 25.0, 50),
    SENSEX("SENSEX", "BSE SENSEX", "BSE", "BFO", "Index", setOf("BSE 30", "BSESENSEX"), 80_000.0, 100.0, 20),
    BANKEX("BANKEX", "BSE BANKEX", "BSE", "BFO", "Index", setOf("BSE BANK"), 58_000.0, 100.0, 15),
    CRUDEOIL("CRUDEOIL", "MCX CRUDE OIL", "MCX", "MCX", "Commodity", setOf("CRUDE", "OIL"), 6_850.0, 50.0, 100),
    NATURALGAS("NATURALGAS", "MCX NATURAL GAS", "MCX", "MCX", "Commodity", setOf("NAT GAS", "GAS"), 240.0, 5.0, 1250),
    GOLD("GOLD", "MCX GOLD", "MCX", "MCX", "Commodity", setOf("GOLDM", "GOLD MINI"), 72_000.0, 100.0, 1),
    SILVER("SILVER", "MCX SILVER", "MCX", "MCX", "Commodity", setOf("SILVERM", "SILVER MINI"), 85_000.0, 250.0, 30),
}

object InstrumentCatalog {
    private val featured = listOf(
        TradeInstrument.NIFTY,
        TradeInstrument.BANKNIFTY,
        TradeInstrument.SENSEX,
        TradeInstrument.CRUDEOIL,
        TradeInstrument.GOLD,
    )

    fun search(query: String): List<TradeInstrument> {
        val term = query.trim().uppercase()
        if (term.isEmpty()) return featured
        return TradeInstrument.entries.filter { instrument ->
            sequenceOf(
                instrument.symbol,
                instrument.displayName,
                instrument.cashExchange,
                instrument.derivativesExchange,
                instrument.category,
            ).plus(instrument.aliases.asSequence()).any { value -> value.uppercase().contains(term) }
        }.sortedWith(compareBy<TradeInstrument> { !it.symbol.startsWith(term) }.thenBy { it.symbol })
    }
}
