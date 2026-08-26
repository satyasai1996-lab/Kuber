package ai.kuber.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Transient broker-connection form. Values are cleared after submission and
 * must only be sent to an HTTPS Kuber backend. Nothing here writes credentials
 * to the APK, preferences, logs, or Android backups.
 */
@Composable
fun BrokerConnectScreen(onConnect: (broker: String, fields: Map<String, String>) -> Unit) {
    var broker by remember { mutableStateOf("angel_one") }
    var apiKey by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Connect broker to Kuber")
        OutlinedTextField(broker, { broker = it }, label = { Text("Broker: angel_one or zerodha") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID / user ID") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text("Temporary auth secret / request token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            onConnect(broker, mapOf("api_key" to apiKey, "client_id" to clientId, "temporary_secret" to secret))
            // Clear all sensitive material immediately after the hand-off.
            apiKey = ""; clientId = ""; secret = ""
        }) { Text("Connect securely") }
    }
}
