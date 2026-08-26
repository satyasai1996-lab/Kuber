package ai.kuber.app

import ai.kuber.app.data.AnalysisResultDto
import ai.kuber.app.data.DemoSessionDto
import ai.kuber.app.data.KuberApiFactory
import ai.kuber.app.data.MarketRefreshDto
import ai.kuber.app.data.OptionContractDto
import ai.kuber.app.data.PortfolioDto
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class KuberScreen(val label: String) {
    HOME("Home"), ANALYSIS("AI Analysis"), GAMMA("Gamma"), OPTIONS("Options"),
    TRADE_PLAN("Trade Plan"), PORTFOLIO("Portfolio"), BACKTEST("Backtest"),
    ALERTS("Alerts"), BROKER("Broker"), SETTINGS("Settings"),
}
/** Kuber is API-driven: authoritative analysis and orders stay in the backend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KuberApp() {
    var activeScreen by remember { mutableStateOf(KuberScreen.HOME) }
    var endpoint by remember { mutableStateOf("https://api.example.com") }
    var demo by remember { mutableStateOf<DemoSessionDto?>(null) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Kuber · ${activeScreen.label}") }) },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = KuberScreen.entries.indexOf(activeScreen)) {
                    KuberScreen.entries.forEach { screen ->
                        Tab(
                            selected = activeScreen == screen,
                            onClick = { activeScreen = screen },
                            text = { Text(screen.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    when (activeScreen) {
                    KuberScreen.HOME -> HomeScreen(endpoint, demo, { endpoint = it }, { demo = it })
                    KuberScreen.ANALYSIS -> AnalysisScreen(demo?.analysis)
                    KuberScreen.GAMMA -> GammaScreen(demo)
                    KuberScreen.OPTIONS -> OptionsScreen(demo?.options)
                    KuberScreen.TRADE_PLAN -> TradePlanScreen(demo?.analysis)
                    KuberScreen.PORTFOLIO -> PortfolioScreen(endpoint)
                    KuberScreen.ALERTS -> AlertsScreen(endpoint)
                    KuberScreen.BROKER -> BrokerConnectScreen(endpoint, { endpoint = it }, { demo = it })
                    KuberScreen.BACKTEST -> BacktestScreen(demo?.analysis)
                    KuberScreen.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(endpoint: String, demo: DemoSessionDto?, onEndpointChange: (String) -> Unit, onDemoStarted: (DemoSessionDto) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Start a safe demo or configure a broker from the Broker tab.") }
    Column(modifier = Modifier.padding(16.dp)) {
        Image(
            painter = painterResource(R.drawable.kuber_market_hero),
            contentDescription = "Kuber market intelligence",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(132.dp),
        )
        Text("Kuber", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text("AI-bot market intelligence with paper trading by default")
        OutlinedTextField(
            value = endpoint,
            onValueChange = { onEndpointChange(it.trimEnd('/')) },
            label = { Text("Kuber API endpoint") },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Button(
            enabled = endpoint.startsWith("https://") || endpoint.startsWith("http://"),
            onClick = {
                scope.launch {
                    status = "Loading paper demo…"
                    runCatching { KuberApiFactory.create(endpoint).startDemo() }
                        .onSuccess { session -> onDemoStarted(session); status = session.notice }
                        .onFailure { error -> status = "Demo unavailable: ${error.message ?: "check the API endpoint"}" }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Start safe paper demo") }
        Button(
            enabled = endpoint.startsWith("https://") || endpoint.startsWith("http://"),
            onClick = {
                scope.launch {
                    status = "Refreshing connected Zerodha data…"
                    runCatching { KuberApiFactory.create(endpoint).refreshMarket("NIFTY", MarketRefreshDto()) }
                        .onSuccess { result -> status = "Live broker snapshot refreshed: ${result.final_bias}. Open Analysis for all seven bot results." }
                        .onFailure { error -> status = "Live refresh unavailable: ${error.message ?: "connect Zerodha first"}" }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Refresh connected market data") }
        demo?.let { session ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${session.symbol} · ${session.source}", style = MaterialTheme.typography.titleMedium)
                    Text("₹${"%.2f".format(session.quote.last_price)} · ${session.gex.regime} GEX · ${session.analysis.final_bias}")
                    Text("${session.analysis.risk.approved.let { if (it) "Risk approved" else "Risk blocked" }} · data ${session.quote.timestamp}")
                }
            }
        }
        Text(status, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AnalysisScreen(analysis: AnalysisResultDto?) {
    if (analysis == null) return NeedSession("Start the safe paper demo first. It runs all seven analysts against one timestamped backend snapshot.")
    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item {
            Text("Seven AI analysts", style = MaterialTheme.typography.headlineSmall)
            Text("Final bias: ${analysis.final_bias} · ${"%.0f".format(analysis.scorecard.agreement_percent)}% agreement")
            if (analysis.scorecard.conflicts.isNotEmpty()) Text("Conflicts: ${analysis.scorecard.conflicts.joinToString()}")
        }
        items(analysis.agents.size) { index ->
            val agent = analysis.agents[index]
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${agent.agent}: ${agent.bias} (${agent.confidence}%)", style = MaterialTheme.typography.titleMedium)
                    Text(agent.evidence.joinToString())
                    if (agent.risks.isNotEmpty()) Text("Risk: ${agent.risks.joinToString()}")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Bull / Bear review", style = MaterialTheme.typography.titleMedium)
                    Text("Bull: ${analysis.debate.bull_argument}")
                    Text("Bear: ${analysis.debate.bear_argument}")
                    Text("Decision: ${analysis.debate.facilitator_summary}")
                    Text("Risk veto: ${if (analysis.risk.approved) "approved" else "blocked"} — ${analysis.risk.reasons.joinToString()}")
                }
            }
        }
    }
}

@Composable
private fun GammaScreen(demo: DemoSessionDto?) {
    if (demo == null) return NeedSession("Start the demo to view the backend-calculated GEX snapshot.")
    val gex = demo.gex
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Gamma / GEX", style = MaterialTheme.typography.headlineSmall)
        Text("${gex.symbol} · ${gex.regime} regime")
        Text("Spot: ₹${"%.2f".format(gex.spot)}")
        Text("Net GEX: ${"%.2f".format(gex.total_gex)}")
        Text("Gamma flip: ${gex.gamma_flip?.let { "₹${"%.2f".format(it)}" } ?: "not found"}")
        Text("Gamma walls: ${gex.gamma_walls.joinToString()}")
        Text("Expiry: ${gex.expiry_set.joinToString()}")
        Text("Source: ${gex.source} · ${gex.timestamp}", modifier = Modifier.padding(top = 8.dp))
        Text("Demo values are fixture data; they are not live market data.", modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OptionsScreen(options: List<OptionContractDto>?) {
    if (options == null) return NeedSession("Start the demo to inspect the normalised option chain and GEX inputs.")
    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item { Text("Options", style = MaterialTheme.typography.headlineSmall) }
        items(options.size) { index ->
            val option = options[index]
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${option.strike} ${option.option_type} · ${option.expiry}", style = MaterialTheme.typography.titleMedium)
                    Text("OI ${option.open_interest} · IV ${option.implied_volatility}% · Gamma ${option.gamma}")
                    Text("LTP ₹${option.last_price} · Volume ${option.volume}")
                }
            }
        }
    }
}

@Composable
private fun TradePlanScreen(analysis: AnalysisResultDto?) {
    if (analysis == null) return NeedSession("Start the demo to view risk-vetted trade plans.")
    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item { Text("Trade plans", style = MaterialTheme.typography.headlineSmall) }
        items(analysis.trade_plans.size) { index ->
            val plan = analysis.trade_plans[index]
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(plan.risk_profile.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                    Text("${plan.direction ?: "HOLD"} · entry ${plan.entry ?: "—"} · stop ${plan.stop_loss ?: "—"}")
                    Text("Targets: ${plan.targets.joinToString()} · quantity ${plan.quantity}")
                    Text("GEX: ${plan.gex_context}")
                    Text(plan.rationale.joinToString())
                }
            }
        }
        item { Text("Paper orders only until the backend release gate and explicit live confirmation pass.", modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun PortfolioScreen(endpoint: String) {
    val scope = rememberCoroutineScope()
    var portfolio by remember { mutableStateOf<PortfolioDto?>(null) }
    var status by remember { mutableStateOf("Refresh to load backend-normalised paper/broker portfolio.") }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Portfolio", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = {
            scope.launch {
                runCatching { KuberApiFactory.create(endpoint).portfolio() }
                    .onSuccess { portfolio = it; status = "Portfolio refreshed." }
                    .onFailure { status = "Portfolio unavailable: check API endpoint." }
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Refresh portfolio") }
        portfolio?.let {
            Text("Funds: ₹${it.funds}", modifier = Modifier.padding(top = 10.dp))
            Text("Positions: ${it.positions.size} · Holdings: ${it.holdings.size}")
        }
        Text(status, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AlertsScreen(endpoint: String) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Refresh to load Kuber alert rules.") }
    var count by remember { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Alerts", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = {
            scope.launch {
                runCatching { KuberApiFactory.create(endpoint).alerts() }
                    .onSuccess { count = it.size; status = "Alert rules refreshed." }
                    .onFailure { status = "Alerts unavailable: check API endpoint." }
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Refresh alerts") }
        Text("Configured alerts: ${count ?: "—"}", modifier = Modifier.padding(top = 8.dp))
        Text(status)
    }
}

@Composable
private fun BacktestScreen(analysis: AnalysisResultDto?) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("No-lookahead backtest", style = MaterialTheme.typography.headlineSmall)
        Text("Kuber accepts only risk-approved bot signals and checks each signal against candles available at that timestamp.")
        Text(
            if (analysis == null) "Start the demo before supplying historical candles to the backend backtest endpoint."
            else "Demo analysis is ready to be converted to a risk-approved historical signal; live data is never used as future knowledge.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SettingsScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Safety and settings", style = MaterialTheme.typography.headlineSmall)
        Text("Broker secrets, access tokens and OpenAI keys remain on the backend. The Android app stores no provider secret.")
        Text("Production uses HTTPS and authentication. Paper mode is default; live orders require a release-gate review and explicit confirmation.", modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NeedSession(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kuber", style = MaterialTheme.typography.headlineMedium)
        Text(message)
    }
}
