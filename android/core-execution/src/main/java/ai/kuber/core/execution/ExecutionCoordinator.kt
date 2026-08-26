package ai.kuber.core.execution

import ai.kuber.core.broker.Broker
import ai.kuber.core.model.analysis.RiskDecision
import ai.kuber.core.model.broker.BrokerName
import ai.kuber.core.model.broker.OrderReceipt
import ai.kuber.core.model.broker.OrderRequest
import ai.kuber.core.model.broker.TradingMode
import ai.kuber.core.model.execution.AuditEvent
import ai.kuber.core.model.market.MarketIntelligence
import java.security.MessageDigest
import java.util.UUID

interface IdempotencyStore { fun contains(key: String): Boolean; fun record(key: String) }
class InMemoryIdempotencyStore : IdempotencyStore { private val keys = mutableSetOf<String>(); override fun contains(key: String) = key in keys; override fun record(key: String) { keys += key } }
interface AuditStore { fun append(event: AuditEvent); fun all(): List<AuditEvent> }
class InMemoryAuditStore : AuditStore { private val events = mutableListOf<AuditEvent>(); override fun append(event: AuditEvent) { events += event }; override fun all() = events.toList() }
class KillSwitch { @Volatile var active: Boolean = false }

data class LiveConfirmation(val orderHash: String, val acknowledgement: String)

/** The only path to order placement. Analyst code does not receive a Broker reference. */
class ExecutionCoordinator(
    private val idempotency: IdempotencyStore = InMemoryIdempotencyStore(),
    private val audits: AuditStore = InMemoryAuditStore(),
    private val killSwitch: KillSwitch = KillSwitch(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun previewHash(request: OrderRequest): String = digest(listOf(request.broker, request.mode, request.exchange, request.tradingSymbol, request.side, request.quantity, request.orderType, request.product, request.price, request.triggerPrice, request.idempotencyKey, request.snapshotId, request.inputVersion).joinToString("|"))

    fun submitPaper(broker: Broker, request: OrderRequest, market: MarketIntelligence, risk: RiskDecision): OrderReceipt = submit(broker, request, market, risk, null)
    fun confirmLive(broker: Broker, request: OrderRequest, market: MarketIntelligence, risk: RiskDecision, confirmation: LiveConfirmation): OrderReceipt {
        require(request.mode == TradingMode.LIVE) { "Live confirmation requires a LIVE order" }
        require(confirmation.acknowledgement == "LIVE") { "User must type LIVE to confirm" }
        require(confirmation.orderHash == previewHash(request)) { "Order changed after confirmation; review it again" }
        return submit(broker, request, market, risk, confirmation)
    }
    private fun submit(broker: Broker, request: OrderRequest, market: MarketIntelligence, risk: RiskDecision, confirmation: LiveConfirmation?): OrderReceipt {
        require(!killSwitch.active) { "Kill switch is active" }
        require(request.snapshotId == market.snapshotId && request.inputVersion == market.inputVersion) { "Order does not match current market snapshot" }
        require(risk.approved && risk.snapshotId == market.snapshotId && risk.inputVersion == market.inputVersion) { "Final Risk Manager did not approve this snapshot" }
        require(request.quantity <= risk.allowedQuantity) { "Order quantity exceeds final Risk Manager limit" }
        require(!idempotency.contains(request.idempotencyKey)) { "Duplicate order blocked" }
        if (request.mode == TradingMode.PAPER) require(request.broker == BrokerName.PAPER) { "Paper mode must use the local paper broker" }
        if (request.mode == TradingMode.LIVE) require(confirmation != null) { "Live order requires confirmation" }
        val receipt = broker.placeOrder(request)
        idempotency.record(request.idempotencyKey)
        audits.append(AuditEvent(UUID.randomUUID().toString(), if (request.mode == TradingMode.PAPER) "PAPER_ORDER" else "LIVE_ORDER", request.broker, request.mode, request.idempotencyKey, receipt.status, clock()))
        return receipt
    }
    fun auditEvents(): List<AuditEvent> = audits.all()
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
