package ai.kuber.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

private enum class KuberScreen(val label: String) {
    HOME("Home"), ANALYSIS("AI Analysis"), GAMMA("Gamma"), OPTIONS("Options"), TRADE_PLAN("Trade Plan"), PORTFOLIO("Portfolio")
}

@Composable
fun KuberApp() {
    var activeScreen by remember { mutableStateOf(KuberScreen.HOME) }
    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    KuberScreen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = activeScreen == screen,
                            onClick = { activeScreen = screen },
                            icon = { Text(screen.label.take(1)) },
                            label = { Text(screen.label) },
                        )
                    }
                }
            },
        ) { _ -> KuberScreenPlaceholder(activeScreen) }
    }
}

@Composable
private fun KuberScreenPlaceholder(screen: KuberScreen) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kuber", style = MaterialTheme.typography.headlineMedium)
        Text(screen.label, style = MaterialTheme.typography.titleLarge)
        Text("Data is loaded only through the Kuber API; broker secrets stay on the backend.")
    }
}
