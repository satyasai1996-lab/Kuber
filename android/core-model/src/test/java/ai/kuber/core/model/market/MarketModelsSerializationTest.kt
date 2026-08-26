package ai.kuber.core.model.market

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MarketModelsSerializationTest {
    @Test
    fun `legacy option JSON without OI change remains compatible`() {
        val legacyJson = """
            {
              "underlying":"NIFTY",
              "strike":22000.0,
              "expiry":"2099-12-31",
              "optionType":"CE",
              "openInterest":100,
              "impliedVolatility":0.2,
              "gamma":0.01,
              "lastPrice":120.0,
              "lotSize":25,
              "capturedAt":1800000000000,
              "source":"zerodha"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<OptionContract>(legacyJson)

        assertEquals(0, decoded.volume)
        assertNull(decoded.openInterestChange)
    }

    @Test
    fun `market intelligence round trips without losing version metadata`() {
        val capturedAt = 1_800_000_000_000
        val quote = Quote("NIFTY", 22_000.0, capturedAt, "zerodha", 1_000, 21_990.0)
        val option = OptionContract(
            underlying = "NIFTY",
            strike = 22_000.0,
            expiry = "2099-12-31",
            optionType = OptionType.CE,
            openInterest = 100,
            impliedVolatility = 0.2,
            gamma = 0.01,
            lastPrice = 120.0,
            lotSize = 25,
            capturedAt = capturedAt,
            source = "zerodha",
        )
        val gex = GexSnapshot(
            snapshotId = "snapshot-1",
            inputVersion = "input-1",
            capturedAt = capturedAt,
            source = "zerodha",
            freshness = DataFreshness.FRESH,
            symbol = "NIFTY",
            spot = 22_000.0,
            expirySet = listOf("2099-12-31"),
            gexByStrike = listOf(GexStrike(22_000.0, 55_000_000.0, 0.0, 55_000_000.0)),
            gexByExpiry = listOf(GexExpiry("2099-12-31", 55_000_000.0, 0.0, 55_000_000.0)),
            totalGex = 55_000_000.0,
            gammaFlip = null,
            callWall = 22_000.0,
            putWall = null,
            gammaWalls = listOf(22_000.0),
            regime = GexRegime.POSITIVE,
        )
        val original = MarketIntelligence(
            snapshotId = "snapshot-1",
            inputVersion = "input-1",
            capturedAt = capturedAt,
            source = "zerodha",
            freshness = DataFreshness.FRESH,
            quote = quote,
            optionChain = listOf(option),
            gexSnapshot = gex,
        )

        val encoded = Json.encodeToString(original)
        val decoded = Json.decodeFromString<MarketIntelligence>(encoded)

        assertEquals(original, decoded)
        assertEquals("input-1", decoded.gexSnapshot.inputVersion)
    }

    @Test
    fun `market intelligence rejects a mismatched GEX version`() {
        val gex = GexSnapshot(
            snapshotId = "snapshot-1",
            inputVersion = "old-input",
            capturedAt = 1_800_000_000_000,
            source = "fixture",
            freshness = DataFreshness.FRESH,
            symbol = "NIFTY",
            spot = 100.0,
            expirySet = emptyList(),
            gexByStrike = emptyList(),
            gexByExpiry = emptyList(),
            totalGex = 0.0,
            gammaFlip = null,
            callWall = null,
            putWall = null,
            gammaWalls = emptyList(),
            regime = GexRegime.NEUTRAL,
        )

        assertThrows(IllegalArgumentException::class.java) {
            MarketIntelligence(
                snapshotId = "snapshot-1",
                inputVersion = "new-input",
                capturedAt = 1_800_000_000_000,
                source = "fixture",
                freshness = DataFreshness.FRESH,
                quote = Quote("NIFTY", 100.0, 1_800_000_000_000, "fixture"),
                optionChain = emptyList(),
                gexSnapshot = gex,
            )
        }
    }
}
