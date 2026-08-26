package ai.kuber.app.data.session

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuberServerSessionTest {
    @Test
    fun sessionRequiresHttpsAndLongRuntimeToken() {
        val http = runCatching { KuberServerSession.create("http://kuber.test", "t".repeat(32)) }
        val shortToken = runCatching { KuberServerSession.create("https://kuber.test", "short") }

        assertTrue(http.exceptionOrNull()?.message?.contains("HTTPS") == true)
        assertTrue(shortToken.exceptionOrNull()?.message?.contains("32") == true)
    }

    @Test
    fun verificationChecksHealthThenAuthenticatedStatusWithoutExposingToken() = runBlocking {
        val token = "trial-token-" + "x".repeat(32)
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (request.url.encodedPath == "/health") {
                """{"status":"ok","service":"kuber"}"""
            } else {
                """{"schema_version":"1.0","data":{"ready":false},"error":null}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val session = KuberServerSession.create("https://kuber.test", token)

        val verification = KuberServerVerifier(client).verify(session)

        assertEquals(listOf("/health", "/api/v1/instruments/status"), requests.map { it.url.encodedPath })
        assertEquals("Bearer $token", requests.last().header("Authorization"))
        assertFalse(verification.catalogReady)
        assertFalse(session.toString().contains(token))
        session.destroy()
        assertTrue(runCatching { session.accessToken() }.isFailure)
    }

    @Test
    fun verificationRejectsUnauthorizedServer() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val isHealthCheck = chain.request().url.encodedPath == "/health"
            val code = if (isHealthCheck) 200 else 401
            val body = if (isHealthCheck) {
                """{"status":"ok","service":"kuber"}"""
            } else {
                "{}"
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("response")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val session = KuberServerSession.create("https://kuber.test", "t".repeat(32))

        val failure = runCatching { KuberServerVerifier(client).verify(session) }

        assertTrue(failure.exceptionOrNull()?.message?.contains("rejected") == true)
    }

    @Test
    fun verificationRejectsNonKuberHttpsEndpoint() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"status":"ok","service":"another-app"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val session = KuberServerSession.create("https://kuber.test", "t".repeat(32))

        val failure = runCatching { KuberServerVerifier(client).verify(session) }

        assertTrue(failure.exceptionOrNull()?.message?.contains("not a Kuber service") == true)
    }
}
