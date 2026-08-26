package ai.kuber.app

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Direct, session-only Kite login. No request token is sent to a Kuber server. */
@Composable
fun BrokerConnectScreen(runtime: LocalTradingRuntime, state: LocalKuberState, sync: () -> Unit) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(state.status) }
    fun complete(requestToken: String) {
        val secret = apiSecret.toCharArray()
        apiSecret = ""
        scope.launch(Dispatchers.IO) {
            runCatching { runtime.connectZerodha(apiKey.trim(), secret, requestToken) }
                .onFailure { runtime.setStatus("Zerodha connection failed: ${it.message ?: "try login again"}") }
            withContext(Dispatchers.Main) { loginUrl = null; sync(); status = runtime.state.status }
        }
    }
    loginUrl?.let { url ->
        Column(Modifier.fillMaxSize()) {
            Text("Sign in to Zerodha Kite", Modifier.padding(16.dp))
            Text("Enter password and 2FA only on Zerodha's page. Kuber reads only the short-lived callback token.", Modifier.padding(horizontal = 16.dp))
            ZerodhaWebView(url, ::complete, Modifier.weight(1f))
            Button(onClick = { loginUrl = null }, modifier = Modifier.padding(16.dp)) { Text("Cancel") }
        }
        return
    }
    Column(Modifier.padding(16.dp)) {
        Text("Direct Zerodha connection")
        Text("For a personal installation, enter your own Kite Connect API key and secret for this session. They are never persisted.")
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("Kite API key") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(apiSecret, { apiSecret = it }, label = { Text("Kite API secret (session only)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(), onClick = {
            status = "Opening Kite login…"
            loginUrl = runCatching { runtime.loginUrl(apiKey.trim()) }.getOrElse { status = "Invalid API key: ${it.message}"; null }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Sign in with Zerodha") }
        if (state.connection.state.name == "CONNECTED") {
            Button(onClick = { runtime.logout(); sync(); status = runtime.state.status }, modifier = Modifier.padding(top = 8.dp)) { Text("Clear phone session") }
        }
        Text(status, Modifier.padding(top = 10.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZerodhaWebView(loginUrl: String, onRequestToken: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context -> WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                request.url.getQueryParameter("request_token")?.takeIf { it.isNotBlank() }?.let { onRequestToken(it); return true }
                return false
            }
        }
        loadUrl(loginUrl)
    } })
}
