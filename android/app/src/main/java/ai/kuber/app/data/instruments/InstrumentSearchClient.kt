package ai.kuber.app.data.instruments

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun interface InstrumentSearchClient {
    suspend fun search(query: InstrumentSearchQuery): InstrumentSearchPageDto
}

class InstrumentSearchApiException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class OkHttpInstrumentSearchClient(
    baseUrl: HttpUrl,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val accessTokenProvider: () -> String? = { null },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : InstrumentSearchClient {
    private val endpoint = requireNotNull(baseUrl.resolve("/api/v1/instruments/search")) {
        "base URL cannot resolve the instrument-search endpoint"
    }

    override suspend fun search(query: InstrumentSearchQuery): InstrumentSearchPageDto {
        require(query.normalizedText.isNotEmpty()) { "instrument search query cannot be blank" }

        val url = endpoint.newBuilder()
            .addQueryParameter("q", query.normalizedText)
            .addQueryParameter(
                "exchanges",
                query.exchanges.sortedBy { it.ordinal }.joinToString(",") { it.wireName },
            )
            .addQueryParameter("limit", query.limit.toString())
            .apply {
                if (query.types.isNotEmpty()) {
                    addQueryParameter(
                        "types",
                        query.types.sortedBy { it.ordinal }.joinToString(",") { it.wireName },
                    )
                }
                query.cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .apply {
                accessTokenProvider()?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()

        httpClient.newCall(request).await().use { response ->
            val body = response.body?.string().orEmpty()
            val envelope = decodeEnvelope(body, response.code)
            val apiError = envelope.error

            if (!response.isSuccessful || apiError != null) {
                throw InstrumentSearchApiException(
                    code = apiError?.code ?: "http_${response.code}",
                    message = apiError?.message ?: "Instrument search failed with HTTP ${response.code}",
                    retryable = apiError?.retryable ?: (response.code >= 500),
                    httpStatus = response.code,
                )
            }

            return envelope.data ?: throw InstrumentSearchApiException(
                code = "invalid_response",
                message = "Instrument search response did not contain data",
                retryable = false,
                httpStatus = response.code,
            )
        }
    }

    private fun decodeEnvelope(body: String, status: Int): InstrumentSearchEnvelopeDto {
        if (body.isBlank()) {
            throw InstrumentSearchApiException(
                code = "empty_response",
                message = "Instrument search returned an empty response",
                retryable = status >= 500,
                httpStatus = status,
            )
        }
        return try {
            json.decodeFromString<InstrumentSearchEnvelopeDto>(body)
        } catch (error: SerializationException) {
            throw InstrumentSearchApiException(
                code = "invalid_response",
                message = "Instrument search returned an invalid response",
                retryable = false,
                httpStatus = status,
                cause = error,
            )
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}
