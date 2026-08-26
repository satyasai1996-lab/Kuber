package ai.kuber.app.data.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class KuberServerSession private constructor(
    val baseUrl: HttpUrl,
    token: String,
) {
    private val tokenCharacters = token.toCharArray()

    @Volatile
    private var active = true

    fun accessToken(): String {
        check(active) { "Kuber server session has been cleared" }
        return tokenCharacters.concatToString()
    }

    fun destroy() {
        tokenCharacters.fill('\u0000')
        active = false
    }

    override fun toString(): String = "KuberServerSession(baseUrl=$baseUrl, token=<redacted>)"

    companion object {
        fun create(baseUrlText: String, token: String): KuberServerSession {
            val baseUrl = requireNotNull(baseUrlText.trim().toHttpUrlOrNull()) {
                "Enter a valid Kuber HTTPS URL"
            }
            require(baseUrl.scheme == "https") { "Kuber requires an HTTPS server URL" }
            require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
                "Do not place credentials in the server URL"
            }
            require(token.length >= 32) { "Kuber access token must contain at least 32 characters" }
            return KuberServerSession(baseUrl, token)
        }
    }
}

data class KuberServerVerification(
    val baseUrl: String,
    val catalogReady: Boolean,
)

class KuberServerVerificationException(message: String) : RuntimeException(message)

class KuberServerVerifier(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun verify(session: KuberServerSession): KuberServerVerification = withContext(Dispatchers.IO) {
        val healthUrl = requireNotNull(session.baseUrl.resolve("/health"))
        val healthRequest = Request.Builder()
            .url(healthUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(healthRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw KuberServerVerificationException("Kuber HTTPS health check returned HTTP ${response.code}")
            }
            val healthBody = response.body?.string().orEmpty()
            val isKuber = runCatching {
                val health = json.parseToJsonElement(healthBody).jsonObject
                health["status"]?.jsonPrimitive?.content == "ok" &&
                    health["service"]?.jsonPrimitive?.content == "kuber"
            }.getOrDefault(false)
            if (!isKuber) {
                throw KuberServerVerificationException("HTTPS endpoint is not a Kuber service")
            }
        }

        val statusUrl = requireNotNull(session.baseUrl.resolve("/api/v1/instruments/status"))
        val statusRequest = Request.Builder()
            .url(statusUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.accessToken()}")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        httpClient.newCall(statusRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) {
                throw KuberServerVerificationException("Kuber rejected the access token")
            }
            if (!response.isSuccessful) {
                throw KuberServerVerificationException("Kuber status check returned HTTP ${response.code}")
            }
            val envelope = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw KuberServerVerificationException("Kuber returned an invalid status response")
            }
            if (envelope["schema_version"]?.jsonPrimitive?.content != "1.0") {
                throw KuberServerVerificationException("Kuber returned an unsupported API schema")
            }
            val ready = runCatching {
                envelope["data"]?.jsonObject?.get("ready")?.jsonPrimitive?.booleanOrNull
            }.getOrNull() ?: throw KuberServerVerificationException(
                "Kuber status response did not contain catalogue readiness",
            )
            KuberServerVerification(session.baseUrl.toString(), ready)
        }
    }
}
