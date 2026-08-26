package ai.kuber.app

import ai.kuber.app.data.BrokerConnectRequestDto
import ai.kuber.app.data.KuberApiFactory
import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DEFAULT_LAN_ENDPOINT = "http://192.168.31.75:8000"

/**
 * Direct sandbox login flow. Zerodha renders its own login and 2FA page inside
 * Kuber. The APK never sees or stores a password, PIN, API secret, or an
 * access token; it sends only the short-lived callback token to the laptop.
 */
@Composable
fun BrokerConnectScreen() {
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(DEFAULT_LAN_ENDPOINT) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Laptop trial endpoint ready. Demo orders only.") }

    fun connectSandbox(requestToken: String) {
        scope.launch {
            loginUrl = null
            status = "Connecting demo session…"
            runCatching {
                KuberApiFactory.create(endpoint).connectBroker(
                    BrokerConnectRequestDto(
                        broker = "zerodha_sandbox",
                        credentials = buildJsonObject { put("request_token", requestToken) },
                    ),
                )
            }.onSuccess { connection ->
                status = "Demo connected: ${connection.connection_reference}. No real-money orders."
            }.onFailure { error ->
                status = "Demo connection failed: ${error.message ?: "check laptop Wi-Fi and retry login"}"
            }
        }
    }

    loginUrl?.let { url ->
        Column(modifier = Modifier.fillMaxSize()) {
            Text("Sign in to Zerodha demo", modifier = Modifier.padding(16.dp))
            Text("Your credentials and 2FA are entered only on Zerodha's page.", modifier = Modifier.padding(horizontal = 16.dp))
            ZerodhaSandboxWebView(
                loginUrl = url,
                onRequestToken = ::connectSandbox,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { loginUrl = null }, modifier = Modifier.padding(16.dp)) { Text("Cancel demo login") }
        }
        return
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Zerodha demo sandbox — LAN trial")
        Text("Real-money orders are locked. Keep the phone and laptop on the same Wi-Fi.")
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it.trimEnd('/') },
            label = { Text("Kuber laptop endpoint") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = endpoint.startsWith("http://"),
            onClick = {
                scope.launch {
                    status = "Opening Zerodha demo login…"
                    runCatching { KuberApiFactory.create(endpoint).zerodhaSandboxLogin().login_url }
                        .onSuccess { url -> loginUrl = url }
                        .onFailure { error ->
                            status = "Cannot reach Kuber laptop: ${error.message ?: "check Wi-Fi"}"
                        }
                }
            },
        ) { Text("Sign in to Zerodha demo") }
        Text(status, modifier = Modifier.padding(top = 12.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZerodhaSandboxWebView(loginUrl: String, onRequestToken: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val callbackToken = request.url.getQueryParameter("request_token")
                        if (!callbackToken.isNullOrBlank()) {
                            onRequestToken(callbackToken)
                            return true
                        }
                        return false
                    }
                }
                loadUrl(loginUrl)
            }
        },
    )
}
