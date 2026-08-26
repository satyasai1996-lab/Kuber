package ai.kuber.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private enum class KuberScreen(val label: String) {
    HOME("Home"), ANALYSIS("AI Analysis"), GAMMA("Gamma"), OPTIONS("Options"),
    TRADE_PLAN("Trade Plan"), PORTFOLIO("Portfolio"), BACKTEST("Backtest"),
    ALERTS("Alerts"), BROKER("Broker"), SETTINGS("Settings")
}

@Composable
fun KuberApp() {
    var activeScreen by remember { mutableStateOf(KuberScreen.HOME) }
    MaterialTheme {
        Scaffold { padding ->
            Column(modifier = Modifier.padding(padding)) {
                if (activeScreen == KuberScreen.BROKER) {
                    BrokerConnectScreen()
                } else {
                    KuberScreenPlaceholder(activeScreen)
                }
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f)) {
                    items(KuberScreen.entries) { screen ->
                        Card(onClick = { activeScreen = screen }, modifier = Modifier.padding(8.dp)) {
                            Text(screen.label, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KuberScreenPlaceholder(screen: KuberScreen) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (screen == KuberScreen.HOME) {
            Image(
                painter = painterResource(R.drawable.kuber_market_hero),
                contentDescription = "Kuber market intelligence visual",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        }
        Text("Kuber", style = MaterialTheme.typography.headlineMedium)
        Text(screen.label, style = MaterialTheme.typography.titleLarge)
        Text(screenDescription(screen))
    }
}

private fun screenDescription(screen: KuberScreen): String = when (screen) {
    KuberScreen.HOME -> "NIFTY/BANKNIFTY watchlist, market regime, alerts, and P&L."
    KuberScreen.ANALYSIS -> "Seven analyst cards, scorecard, conflicts, debate, fund-manager decision, and risk veto."
    KuberScreen.GAMMA -> "Shared validated GEX curve, Gamma Flip, regime, walls, expiry, source, and timestamp."
    KuberScreen.OPTIONS -> "Option chain, OI, volume, IV, Greeks, PCR, and anomalies."
    KuberScreen.TRADE_PLAN -> "Aggressive, neutral, and conservative paper plans; live order needs separate confirmation."
    KuberScreen.PORTFOLIO -> "Broker-normalized holdings, positions, P&L, margin, and exposure."
    KuberScreen.BACKTEST -> "No-lookahead backtests over risk-approved AI-bot signals only."
    KuberScreen.ALERTS -> "Price, GEX regime, Gamma Flip, options-anomaly, and AI-decision alerts."
    KuberScreen.BROKER -> "LAN trial: connect the Zerodha demo sandbox to this laptop. Real-money broker orders remain locked."
    KuberScreen.SETTINGS -> "Risk limits, AI provider selection, notification preferences, and secure session controls."
}
