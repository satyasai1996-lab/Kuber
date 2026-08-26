package ai.kuber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentCatalogTest {
    @Test
    fun searchesIndexByNameAndAlias() {
        assertEquals(TradeInstrument.NIFTY, InstrumentCatalog.search("nifty").first())
        assertTrue(InstrumentCatalog.search("bse 30").contains(TradeInstrument.SENSEX))
    }

    @Test
    fun searchesMcxUniverseByExchangeAndCommodityName() {
        val mcx = InstrumentCatalog.search("mcx")
        assertTrue(mcx.contains(TradeInstrument.CRUDEOIL))
        assertTrue(mcx.contains(TradeInstrument.GOLD))
        assertEquals(listOf(TradeInstrument.CRUDEOIL), InstrumentCatalog.search("crude"))
    }

    @Test
    fun emptySearchReturnsFeaturedInstrumentsWithoutPrices() {
        assertEquals(TradeInstrument.NIFTY, InstrumentCatalog.search("").first())
        assertTrue(InstrumentCatalog.search("").contains(TradeInstrument.SENSEX))
    }
}
