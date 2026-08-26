package ai.kuber.core.model.execution

import ai.kuber.core.model.broker.BrokerName
import ai.kuber.core.model.broker.TradingMode
import kotlinx.serialization.Serializable

@Serializable
data class AuditEvent(
    val eventId: String,
    val action: String,
    val broker: BrokerName,
    val mode: TradingMode,
    val requestId: String,
    val result: String,
    val createdAt: Long,
)
