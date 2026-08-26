package ai.kuber.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Interceptor
import okhttp3.Response

/** Stores only the Kuber session token; broker secrets are never present on Android. */
class SessionTokenStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "kuber_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): String? = preferences.getString("api_token", null)
    fun save(token: String) = preferences.edit().putString("api_token", token).apply()
    fun clear() = preferences.edit().remove("api_token").apply()
}

class KuberAuthInterceptor(private val tokens: SessionTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.get()
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        return chain.proceed(request)
    }
}
