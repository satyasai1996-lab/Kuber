package ai.kuber.app

import ai.kuber.core.model.analysis.Bias
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class KuberScreen(val label: String) {
    HOME("Home"), ANALYSIS("AI bots"), GAMMA("GEX"), OPTIONS("Options"),
    TRADE_PLAN("Plans"), BACKTEST("Backtest"), PORTFOLIO("Paper"), BROKER("Broker"), SETTINGS("Safety"),
}

/** Kuber presentation root. Authoritative live market data will arrive through the authenticated API boundary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KuberApp() {
    val runtime = remember { LocalTradingRuntime() }
    var state by remember { mutableStateOf(runtime.state) }
    var active by remember { mutableStateOf(KuberScreen.HOME) }
    fun sync() { state = runtime.state }
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Kuber · ${active.label}") }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = KuberScreen.entries.indexOf(active)) {
                    KuberScreen.entries.forEach { screen ->
                        Tab(selected = active == screen, onClick = { active = screen }, text = { Text(screen.label) })
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (active) {
                        KuberScreen.HOME -> HomeScreen(runtime, state, ::sync)
                        KuberScreen.ANALYSIS -> AnalysisScreen(state)
                        KuberScreen.GAMMA -> GammaScreen(state)
                        KuberScreen.OPTIONS -> OptionsScreen(state)
                        KuberScreen.TRADE_PLAN -> TradePlanScreen(runtime, state, ::sync)
                        KuberScreen.BACKTEST -> BacktestScreen(runtime, state, ::sync)
                        KuberScreen.PORTFOLIO -> PortfolioScreen(runtime, state)
                        KuberScreen.BROKER -> BrokerConnectScreen(runtime, state, ::sync)
                        KuberScreen.SETTINGS -> SafetyScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(runtime: LocalTradingRuntime, state: LocalKuberState, sync: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = InstrumentCatalog.search(searchQuery)
    LazyColumn(Modifier.padding(16.dp)) {
        item {
        Text("Kuber trading desk", style = MaterialTheme.typography.headlineSmall)
        Text("Pick an instrument, inspect its chain, then run the seven-agent decision workflow.")
        }
        item { Column(Modifier.padding(top = 10.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search NIFTY, SENSEX or MCX") },
                placeholder = { Text("Example: CRUDEOIL, GOLD, BANKNIFTY") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(if (searchQuery.isBlank()) "Popular instruments" else "${searchResults.size} matching instruments", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            searchResults.take(8).forEach { instrument ->
                AssistChip(
                    onClick = { runtime.selectInstrument(instrument); searchQuery = instrument.symbol; sync() },
                    label = { Text(if (state.selectedInstrument == instrument) "✓ ${instrument.displayName} · ${instrument.derivativesExchange}" else "${instrument.displayName} · ${instrument.derivativesExchange}") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (searchResults.isEmpty()) Text("No supported NSE, BSE or MCX instrument matches this search.")
            Text("Selected: ${state.selectedInstrument.symbol} · cash ${state.selectedInstrument.cashExchange} · derivatives ${state.selectedInstrument.derivativesExchange}", style = MaterialTheme.typography.bodySmall)
        } }
        item {
        Button(enabled = !busy && state.connection.state.name == "CONNECTED" && state.selectedInstrument.directZerodhaSupported, onClick = {
            busy = true
            scope.launch(Dispatchers.IO) {
                runCatching { runtime.refreshZerodha() }.onFailure { runtime.setStatus("Live refresh failed: ${it.message ?: "check Kite session"}") }
                withContext(Dispatchers.Main) { sync(); busy = false }
            }
        }, modifier = Modifier.padding(top = 12.dp)) { Text("Load verified live snapshot") }
        if (!state.selectedInstrument.directZerodhaSupported) {
            Text("${state.selectedInstrument.displayName} needs the authenticated backend adapter before live data can be shown.", style = MaterialTheme.typography.bodySmall)
        }
        }
        item { SnapshotCard(state) }
        item { Card(Modifier.fillMaxWidth().padding(top = 12.dp)) { Column(Modifier.padding(12.dp)) {
            Text("Workflow status", style = MaterialTheme.typography.titleMedium)
            Text("1. Market snapshot  →  2. Option/GEX  →  3. AI debate  →  4. Risk plans  →  5. Paper or reviewed live gate")
            Text("No price is generated by search. A price appears only after a verified provider response.", style = MaterialTheme.typography.bodySmall)
        } } }
    }
}

@Composable
private fun SnapshotCard(state: LocalKuberState) {
    Card(Modifier.fillMaxWidth().padding(top = 14.dp)) { Column(Modifier.padding(12.dp)) {
        val market = state.market
        Text(when {
            market == null -> "WAITING FOR VERIFIED MARKET DATA"
            state.liveData -> "VERIFIED LIVE SNAPSHOT"
            else -> "OFFLINE TEST FIXTURE — NOT LIVE"
        }, style = MaterialTheme.typography.titleMedium)
        if (market != null) {
            Text("${market.quote.symbol}  ₹${"%.2f".format(market.quote.lastPrice)} · ${market.gexSnapshot.regime}")
            Text("Snapshot ${market.inputVersion.take(12)} · ${market.quote.source}")
        }
        Text(state.status, Modifier.padding(top = 6.dp))
    } }
}

@Composable
private fun AnalysisScreen(state: LocalKuberState) {
    val analysis = state.analysis ?: return NeedSnapshot()
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text("Required bot workflow", style = MaterialTheme.typography.headlineSmall)
            Text("Schema validation → scorecard/conflicts → Bull → Bear → rebuttals → facilitator → fund manager → Risk Manager → three plans → gate")
            Text("Final: ${analysis.finalBias} · agreement ${"%.0f".format(analysis.scorecard.agreementPercent)}%")
        }
        items(analysis.analysts.size) { i ->
            val result = analysis.analysts[i]
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(Modifier.padding(12.dp)) {
                Text("${result.analyst}: ${result.bias} (${result.confidence}%)", style = MaterialTheme.typography.titleMedium)
                Text(result.evidence.joinToString())
                if (result.risks.isNotEmpty()) Text("Risk: ${result.risks.joinToString()}")
            } }
        }
        item { Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(Modifier.padding(12.dp)) {
            Text("Debate and final control", style = MaterialTheme.typography.titleMedium)
            Text("Bull: ${analysis.debate.bullArgument}")
            Text("Bear: ${analysis.debate.bearArgument}")
            Text("Rebuttals: ${analysis.debate.bullRebuttal} / ${analysis.debate.bearRebuttal}")
            Text("Facilitator: ${analysis.debate.facilitatorSummary}")
            Text("Fund manager: ${analysis.fundManager.bias} · GEX ${analysis.fundManager.gexAlignment}")
            Text("Risk Manager: ${if (analysis.finalRisk.approved) "APPROVED" else "BLOCKED"} — ${analysis.finalRisk.reasons.joinToString()}")
        } } }
    }
}

@Composable
private fun GammaScreen(state: LocalKuberState) {
    val gex = state.market?.gexSnapshot ?: return NeedSnapshot()
    Column(Modifier.padding(16.dp)) {
        Text("Gamma exposure", style = MaterialTheme.typography.headlineSmall)
        Text("${gex.symbol} · ${gex.regime} · net ${"%.0f".format(gex.totalGex)}")
        Text("Gamma flip: ${gex.gammaFlip?.let { "₹${"%.2f".format(it)}" } ?: "not found"}")
        Text("Walls: ${gex.gammaWalls.joinToString()}")
        Text("Calculated only from the same immutable option-chain snapshot.", Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun OptionsScreen(state: LocalKuberState) {
    val market = state.market ?: return NeedSnapshot()
    val analytics = state.options
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text("${market.quote.symbol} option chain", style = MaterialTheme.typography.headlineSmall)
            Text("${if (state.liveData) "Broker snapshot" else "Local deterministic fixture"} · ${market.optionChain.firstOrNull()?.expiry ?: "—"} expiry")
            analytics?.let { Text("PCR ${it.putCallRatios.openInterest?.let { ratio -> "%.2f".format(ratio) } ?: "—"} · IV skew ${it.putMinusCallIvSkew?.let { skew -> "%.2f".format(skew) } ?: "—"}") }
        }
        items(market.optionChain.size) { i ->
            val option = market.optionChain[i]
            Card(Modifier.fillMaxWidth().padding(top = 7.dp)) { Column(Modifier.padding(10.dp)) {
                Text("${option.strike} ${option.optionType} · ${option.expiry}", style = MaterialTheme.typography.titleMedium)
                Text("LTP ₹${option.lastPrice} · OI ${option.openInterest} · IV ${"%.2f".format(option.impliedVolatility * 100)}% · gamma ${option.gamma}")
            } }
        }
    }
}

@Composable
private fun BacktestScreen(runtime: LocalTradingRuntime, state: LocalKuberState, sync: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<LocalBacktestResult?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No-lookahead paper backtest", style = MaterialTheme.typography.headlineSmall)
        Text("${state.selectedInstrument.symbol} · 21 synthetic historical bars · fast/slow moving-average crossover")
        Text("At each bar, the strategy sees only prior closed bars. This is a fixture validation, not a claim of historical market performance.")
        Button(onClick = { scope.launch(Dispatchers.Default) { result = runtime.runPaperBacktest(); withContext(Dispatchers.Main) { sync() } } }) { Text("Run local backtest") }
        result?.let { backtest ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("Completed", style = MaterialTheme.typography.titleMedium)
                Text("Trades ${backtest.trades} · win rate ${if (backtest.trades == 0) "—" else "%.0f".format(backtest.wins * 100.0 / backtest.trades)}%")
                Text("Return ${"%.2f".format(backtest.netReturnPercent)}% · max drawdown ${"%.2f".format(backtest.maxDrawdownPercent)}%")
                Text(backtest.status, style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
private fun TradePlanScreen(runtime: LocalTradingRuntime, state: LocalKuberState, sync: () -> Unit) {
    val analysis = state.analysis ?: return NeedSnapshot()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.padding(16.dp)) {
        item { Text("Three risk profiles", style = MaterialTheme.typography.headlineSmall) }
        items(analysis.tradePlans.size) { i ->
            val plan = analysis.tradePlans[i]
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(Modifier.padding(12.dp)) {
                Text(plan.profile.name, style = MaterialTheme.typography.titleMedium)
                Text("${plan.direction} · entry ${plan.entry ?: "—"} · stop ${plan.stopLoss ?: "—"}")
                Text("Targets ${plan.targets.joinToString()} · R/R ${plan.rewardRisk ?: "—"} · qty ${plan.quantity}")
                Button(enabled = plan.quantity > 0 && plan.direction in setOf(Bias.BULLISH, Bias.BEARISH), onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { runtime.submitPaper(plan.profile) }.onFailure { runtime.setStatus("Paper order blocked: ${it.message}") }
                        withContext(Dispatchers.Main) { sync() }
                    }
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Execute local paper order") }
                LivePlanControl(runtime, state, plan.profile, plan.quantity, sync)
            } }
        }
        item { Text("Live orders are not exposed until the final on-device confirmation UI is complete.", Modifier.padding(top = 12.dp)) }
    }
}

@Composable
private fun LivePlanControl(runtime: LocalTradingRuntime, state: LocalKuberState, profile: ai.kuber.core.model.analysis.RiskProfile, approvedQuantity: Int, sync: () -> Unit) {
    val scope = rememberCoroutineScope()
    var exchange by remember { mutableStateOf("NFO") }
    var symbol by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf(approvedQuantity.toString()) }
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var hash by remember { mutableStateOf<String?>(null) }
    var acknowledgement by remember { mutableStateOf("") }
    val canReview = state.liveData && state.connection.state.name == "CONNECTED" && symbol.isNotBlank() && quantity.toIntOrNull() != null
    Column(Modifier.padding(top = 8.dp)) {
        Text("Live Kite order — requires review", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(exchange, { exchange = it.uppercase(); hash = null }, label = { Text("Exchange (NFO/NSE/BSE)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(symbol, { symbol = it.uppercase(); hash = null }, label = { Text("Exact Kite trading symbol") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit); hash = null }, label = { Text("Quantity (max $approvedQuantity)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = canReview, onClick = {
            scope.launch(Dispatchers.IO) {
                hash = runCatching { runtime.previewLive(profile, exchange, symbol, quantity.toInt(), requestId) }.getOrElse { runtime.setStatus("Live review blocked: ${it.message}"); null }
                withContext(Dispatchers.Main) { sync() }
            }
        }) { Text("Generate order review") }
        hash?.let { reviewed ->
            Text("Review hash: ${reviewed.take(16)}…", Modifier.padding(top = 4.dp))
            Text("Verify broker symbol, direction, quantity and risk. Type LIVE to send this exact reviewed order.")
            OutlinedTextField(acknowledgement, { acknowledgement = it }, label = { Text("Type LIVE") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = acknowledgement == "LIVE", onClick = {
                scope.launch(Dispatchers.IO) {
                    runCatching { runtime.submitLive(profile, exchange, symbol, quantity.toInt(), requestId, reviewed, acknowledgement) }
                        .onFailure { runtime.setStatus("Live order blocked: ${it.message}") }
                    withContext(Dispatchers.Main) { sync(); acknowledgement = ""; hash = null; requestId = UUID.randomUUID().toString() }
                }
            }) { Text("Submit reviewed live order") }
        }
        if (!state.liveData) Text("Disabled until a direct live Zerodha snapshot has been refreshed.")
    }
}

@Composable
private fun PortfolioScreen(runtime: LocalTradingRuntime, state: LocalKuberState) {
    val portfolio = runtime.paperPortfolio()
    Column(Modifier.padding(16.dp)) {
        Text("Local paper portfolio", style = MaterialTheme.typography.headlineSmall)
        Text("Cash ₹${"%.2f".format(portfolio.funds)} · orders ${portfolio.orders.size}")
        portfolio.positions.forEach { Text("${it.tradingSymbol}: ${it.quantity} @ ${it.averagePrice} · P&L ${it.pnl}") }
        Text("Audit events ${runtime.auditEvents().size}", Modifier.padding(top = 10.dp))
        Text(state.status, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SafetyScreen() = Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Safety boundary", style = MaterialTheme.typography.headlineSmall)
    Text("Search selects an instrument only. It never generates a price or marks cached data as live.")
    Text("Production market data, bot decisions and orders require an authenticated TLS API and broker session.")
    Text("Live execution requires a fresh snapshot, final Risk Manager approval, order review and explicit confirmation.")
    Text("No broker password, PIN, API secret, or access token is saved in the APK.")
}

@Composable
private fun NeedSnapshot() = Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Search an instrument and connect verified market data first.")
}
