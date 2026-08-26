package ai.kuber.app

import ai.kuber.app.data.BrokerConnectRequestDto
import ai.kuber.app.data.DemoSessionDto
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

/**
 * Real Kite OAuth flow. Zerodha renders its own login and 2FA page inside
 * Kuber. The APK never sees or stores a password, PIN, API secret, or access
 * token; it sends only the short-lived callback token to the Kuber backend.
 */
@Composable
fun BrokerConnectScreen(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    onDemoStarted: (DemoSessionDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Start paper demo, or connect your own Zerodha Kite app.") }

    fun connectZerodha(requestToken: String) {
        scope.launch {
            loginUrl = null
            status = "Connecting demo session…"
            runCatching {
                KuberApiFactory.create(endpoint).connectBroker(
                    BrokerConnectRequestDto(
                        broker = "zerodha",
                        credentials = buildJsonObject { put("request_token", requestToken) },
                    ),
                )
            }.onSuccess { connection ->
                status = "Zerodha connected: ${connection.connection_reference}. Live orders remain controlled."
            }.onFailure { error ->
                status = "Zerodha connection failed: ${error.message ?: "check backend configuration and retry login"}"
            }
        }
    }

    loginUrl?.let { url ->
        Column(modifier = Modifier.fillMaxSize()) {
            Text("Sign in to Zerodha", modifier = Modifier.padding(16.dp))
            Text("Your credentials and 2FA are entered only on Zerodha's page.", modifier = Modifier.padding(horizontal = 16.dp))
            ZerodhaWebView(
                loginUrl = url,
                onRequestToken = ::connectZerodha,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { loginUrl = null }, modifier = Modifier.padding(16.dp)) { Text("Cancel Zerodha login") }
        }
        return
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Broker and paper-trading setup")
        Text("The broker key and secret belong only on your Kuber backend. The APK never stores them.")
        OutlinedTextField(
            value = endpoint,
            onValueChange = { onEndpointChange(it.trimEnd('/')) },
            label = { Text("Kuber HTTPS API endpoint") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = endpoint.startsWith("https://") || endpoint.startsWith("http://"),
            onClick = {
                scope.launch {
                    status = "Opening your Zerodha Kite login…"
                    runCatching { KuberApiFactory.create(endpoint).zerodhaLogin().login_url }
                        .onSuccess { url -> loginUrl = url }
                        .onFailure { error ->
                            status = "Cannot start Zerodha login: ${error.message ?: "configure the Kuber backend first"}"
                        }
                }
            },
        ) { Text("Connect Zerodha") }
        Button(
            enabled = endpoint.startsWith("https://") || endpoint.startsWith("http://"),
            onClick = {
                scope.launch {
                    status = "Starting fixture-backed paper demo…"
                    runCatching { KuberApiFactory.create(endpoint).startDemo() }
                        .onSuccess { demo ->
                            onDemoStarted(demo)
                            status = "Paper demo ready. Its source is clearly labelled demo_fixture."
                        }
                        .onFailure { error -> status = "Cannot start paper demo: ${error.message ?: "check API endpoint"}" }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Start safe paper demo") }
        Text(status, modifier = Modifier.padding(top = 12.dp))
    }
}
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZerodhaWebView(loginUrl: String, onRequestToken: (String) -> Unit, modifier: Modifier = Modifier) {
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
