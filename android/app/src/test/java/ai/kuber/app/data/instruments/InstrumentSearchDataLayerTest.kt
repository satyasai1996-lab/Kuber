package ai.kuber.app.data.instruments

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentSearchDataLayerTest {
    @Test
    fun httpClientBuildsAuthenticatedFilteredRequestAndParsesCanonicalInstruments() = runBlocking {
        var capturedRequest: okhttp3.Request? = null
        val responseJson = """
            {
              "schema_version":"1.0",
              "request_id":"req-1",
              "server_time":"2026-08-26T12:00:00Z",
              "data":{
                "items":[
                  {
                    "instrument_id":"kuber:nse:reliance:eq",
                    "exchange":"NSE",
                    "segment":"NSE-CM",
                    "tradingsymbol":"RELIANCE",
                    "display_name":"Reliance Industries",
                    "instrument_type":"EQ",
                    "underlying":"RELIANCE",
                    "lot_size":1,
                    "tick_size":0.05,
                    "currency":"INR"
                  },
                  {
                    "instrument_id":"kuber:bse:500325:eq",
                    "exchange":"BSE",
                    "segment":"BSE-CM",
                    "tradingsymbol":"500325",
                    "display_name":"Reliance Industries",
                    "instrument_type":"EQ",
                    "underlying":"RELIANCE",
                    "lot_size":1,
                    "tick_size":0.05,
                    "currency":"INR"
                  },
                  {
                    "instrument_id":"kuber:mcx:gold:2026-10-05:fut",
                    "exchange":"MCX",
                    "segment":"MCX-FO",
                    "tradingsymbol":"GOLD26OCTFUT",
                    "display_name":"Gold October 2026 Future",
                    "instrument_type":"FUT",
                    "underlying":"GOLD",
                    "expiry":"2026-10-05",
                    "lot_size":1,
                    "tick_size":1.0,
                    "currency":"INR",
                    "provider_token":"must-not-be-modelled"
                  }
                ],
                "next_cursor":"page-2",
                "catalog_version":"sha256:catalog",
                "as_of":"2026-08-26T00:00:00Z"
              },
              "error":null
            }
        """.trimIndent()
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val client = OkHttpInstrumentSearchClient(
            baseUrl = "https://kuber.test/ignored-prefix".toHttpUrl(),
            httpClient = okHttp,
            accessTokenProvider = { "kuber-access-token" },
        )

        val page = client.search(
            InstrumentSearchQuery(
                text = " Reliance & Gold ",
                exchanges = setOf(InstrumentExchange.NSE, InstrumentExchange.BSE, InstrumentExchange.MCX),
                types = setOf(InstrumentType.EQUITY, InstrumentType.FUTURE),
                limit = 20,
                cursor = "cursor 1",
            ),
        )

        val request = requireNotNull(capturedRequest)
        assertEquals("/api/v1/instruments/search", request.url.encodedPath)
        assertEquals("Reliance & Gold", request.url.queryParameter("q"))
        assertEquals("NSE,BSE,MCX", request.url.queryParameter("exchanges"))
        assertEquals("EQ,FUT", request.url.queryParameter("types"))
        assertEquals("20", request.url.queryParameter("limit"))
        assertEquals("cursor 1", request.url.queryParameter("cursor"))
        assertEquals("Bearer kuber-access-token", request.header("Authorization"))
        assertEquals(listOf("NSE", "BSE", "MCX"), page.items.map { it.exchange })
        assertEquals("2026-10-05", page.items.last().expiry)
        assertEquals(1.0, page.items.last().tick_size, 0.0)
        assertEquals("page-2", page.next_cursor)
    }

    @Test
    fun httpClientMapsStructuredApiError() = runBlocking {
        val errorJson = """
            {
              "schema_version":"1.0",
              "request_id":"req-2",
              "server_time":"2026-08-26T12:00:00Z",
              "data":null,
              "error":{"code":"catalog_unavailable","message":"Catalog is unavailable","retryable":true}
            }
        """.trimIndent()
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body(errorJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val client = OkHttpInstrumentSearchClient("https://kuber.test".toHttpUrl(), okHttp)

        val thrown = runCatching { client.search(InstrumentSearchQuery("gold")) }.exceptionOrNull()

        assertTrue(thrown is InstrumentSearchApiException)
        val error = thrown as InstrumentSearchApiException
        assertEquals("catalog_unavailable", error.code)
        assertEquals(503, error.httpStatus)
        assertTrue(error.retryable)
    }

    @Test
    fun repositoryPublishesLoadingThenResultsWithoutMergingAmbiguousListings() = runBlocking {
        lateinit var repository: InstrumentSearchRepository
        val query = InstrumentSearchQuery("reliance")
        val nse = instrument("kuber:nse:reliance:eq", "NSE", "RELIANCE")
        val bse = instrument("kuber:bse:500325:eq", "BSE", "500325")
        val client = InstrumentSearchClient {
            assertEquals(InstrumentSearchState.Loading(query), repository.state.value)
            InstrumentSearchPageDto(
                items = listOf(nse, bse),
                next_cursor = "next",
                catalog_version = "v1",
                as_of = "2026-08-26T00:00:00Z",
            )
        }
        repository = InstrumentSearchRepository(client)

        val result = repository.search(query)

        assertTrue(result is InstrumentSearchState.Results)
        val results = result as InstrumentSearchState.Results
        assertEquals(listOf(nse.instrument_id, bse.instrument_id), results.instruments.map { it.instrument_id })
        assertEquals("next", results.nextCursor)
        assertEquals(result, repository.state.value)
    }

    @Test
    fun repositoryPublishesEmptyForBlankQueryWithoutCallingNetwork() = runBlocking {
        var called = false
        val repository = InstrumentSearchRepository(InstrumentSearchClient {
            called = true
            InstrumentSearchPageDto()
        })

        val result = repository.search(InstrumentSearchQuery("   "))

        assertTrue(result is InstrumentSearchState.Empty)
        assertFalse(called)
    }

    @Test
    fun repositoryPreservesCatalogMetadataForEmptyResults() = runBlocking {
        val repository = InstrumentSearchRepository(InstrumentSearchClient {
            InstrumentSearchPageDto(
                items = emptyList(),
                catalog_version = "v2",
                as_of = "2026-08-26T01:00:00Z",
            )
        })

        val result = repository.search(InstrumentSearchQuery("unknown"))

        assertTrue(result is InstrumentSearchState.Empty)
        val empty = result as InstrumentSearchState.Empty
        assertEquals("v2", empty.catalogVersion)
        assertEquals("2026-08-26T01:00:00Z", empty.asOf)
    }

    @Test
    fun repositoryMapsNetworkFailureToRetryableErrorAndCanReset() = runBlocking {
        val repository = InstrumentSearchRepository(InstrumentSearchClient { throw IOException("offline") })

        val result = repository.search(InstrumentSearchQuery("gold"))

        assertTrue(result is InstrumentSearchState.Error)
        val error = result as InstrumentSearchState.Error
        assertEquals("network_error", error.code)
        assertTrue(error.retryable)
        assertNull(error.httpStatus)
        repository.reset()
        assertEquals(InstrumentSearchState.Idle, repository.state.value)
    }

    @Test
    fun slowerPreviousSearchCannotOverwriteTheLatestState() = runBlocking {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val repository = InstrumentSearchRepository(InstrumentSearchClient { query ->
            if (query.normalizedText == "old") {
                oldRequestStarted.complete(Unit)
                releaseOldRequest.await()
            }
            InstrumentSearchPageDto(items = listOf(instrument("id:${query.normalizedText}", "NSE", query.normalizedText)))
        })

        val oldResult = async { repository.search(InstrumentSearchQuery("old")) }
        oldRequestStarted.await()
        repository.search(InstrumentSearchQuery("new"))
        releaseOldRequest.complete(Unit)
        oldResult.await()

        val latest = repository.state.value
        assertTrue(latest is InstrumentSearchState.Results)
        latest as InstrumentSearchState.Results
        assertEquals("id:new", latest.instruments.single().instrument_id)
    }

    private fun instrument(id: String, exchange: String, symbol: String) = InstrumentDto(
        instrument_id = id,
        exchange = exchange,
        segment = "$exchange-CM",
        tradingsymbol = symbol,
        display_name = "Reliance Industries",
        instrument_type = "EQ",
        underlying = "RELIANCE",
        lot_size = 1,
        tick_size = 0.05,
    )
}
