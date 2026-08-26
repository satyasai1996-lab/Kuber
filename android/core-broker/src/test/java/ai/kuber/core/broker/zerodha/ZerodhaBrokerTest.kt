package ai.kuber.core.broker.zerodha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZerodhaBrokerTest {
    @Test fun `session-only OAuth exchange then quote uses Kite authorization`() {
        val captured = mutableListOf<String>()
        val transport = HttpTransport { method, url, headers, body ->
            captured += "$method $url ${headers["Authorization"].orEmpty()} ${body.orEmpty()}"
            when {
                url.endsWith("/session/token") -> TransportResponse(200, "{\"status\":\"success\",\"data\":{\"access_token\":\"session-token\",\"user_id\":\"AB1234\"}}")
                url.contains("/quote?") -> TransportResponse(200, "{\"status\":\"success\",\"data\":{\"NSE:NIFTY 50\":{\"last_price\":22050.5,\"volume\":100,\"average_price\":22020.0}}}")
                else -> error("unexpected $url")
            }
        }
        val broker = ZerodhaBroker(transport, clock = { 1_000_000L })
        val secret = "personal-secret".toCharArray()
        val connection = broker.auth.exchangeRequestToken("kite-key", secret, "request-token")
        assertTrue(connection.state.name == "CONNECTED")
        assertTrue(secret.all { it == '\u0000' })
        val quote = broker.getQuote("NIFTY")
        assertEquals(22050.5, quote.lastPrice, 0.0)
        assertTrue(captured.any { it.contains("token kite-key:session-token") })
        assertFalse(captured.any { it.contains("personal-secret") })
    }

    @Test fun `logout clears volatile broker session`() {
        val broker = ZerodhaBroker(HttpTransport { _, _, _, _ -> TransportResponse(500, "{}") })
        broker.logout()
        assertFalse(broker.connection.state.name == "CONNECTED")
        try { broker.getQuote("NIFTY"); throw AssertionError("expected disconnected failure") } catch (_: IllegalStateException) { }
    }
}
