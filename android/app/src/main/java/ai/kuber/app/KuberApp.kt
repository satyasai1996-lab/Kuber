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
    TRADE_PLAN("Plans"), PORTFOLIO("Paper"), BROKER("Broker"), SETTINGS("Safety"),
}

/** All Kuber analysis, paper execution and broker calls run in this app process. */
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
    Column(Modifier.padding(16.dp)) {
        Text("On-phone market intelligence", style = MaterialTheme.typography.headlineSmall)
        Text("No Kuber API endpoint, laptop, or Kuber cloud service is used.")
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch(Dispatchers.IO) {
                runCatching { runtime.loadPaperDemo() }.onFailure { runtime.setStatus("Demo failed: ${it.message}") }
                withContext(Dispatchers.Main) { sync(); busy = false }
            }
        }, modifier = Modifier.padding(top = 12.dp)) { Text("Load local paper demo") }
        Button(enabled = !busy && state.connection.state.name == "CONNECTED", onClick = {
            busy = true
            scope.launch(Dispatchers.IO) {
                runCatching { runtime.refreshZerodha() }.onFailure { runtime.setStatus("Live refresh failed: ${it.message ?: "check Kite session"}") }
                withContext(Dispatchers.Main) { sync(); busy = false }
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Refresh live Zerodha data") }
        SnapshotCard(state)
    }
}

@Composable
private fun SnapshotCard(state: LocalKuberState) {
    Card(Modifier.fillMaxWidth().padding(top = 14.dp)) { Column(Modifier.padding(12.dp)) {
        val market = state.market
        Text(if (state.liveData) "DIRECT ZERODHA SNAPSHOT" else "LOCAL PAPER FIXTURE", style = MaterialTheme.typography.titleMedium)
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
            Text("Option chain", style = MaterialTheme.typography.headlineSmall)
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
    Text("Kuber has no configured server endpoint. Market data and orders go directly from this phone to the broker over HTTPS.")
    Text("Kite credentials are entered on Kite's own page. The session token is held only in memory and cleared at logout or app process death.")
    Text("Paper mode is local. Live execution requires a fresh snapshot, final Risk Manager approval, order-hash review, and typing LIVE.")
    Text("No broker password, PIN, API secret, or access token is saved in the APK.")
}

@Composable
private fun NeedSnapshot() = Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Load the local paper demo or connect Zerodha first.")
}
