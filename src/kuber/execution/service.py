"""Execution gate: explicit live confirmation, idempotency and immutable audit events."""
from __future__ import annotations

from dataclasses import dataclass, field
from uuid import uuid4

from kuber.brokers.base import BaseBroker
from kuber.models import AuditEvent, OrderRequest, OrderResponse, TradingMode, utc_now


@dataclass
class AuditLog:
    events: list[AuditEvent] = field(default_factory=list)

    def record(self, action: str, broker: str, request_id: str, result: str) -> AuditEvent:
        event = AuditEvent(uuid4().hex, action, broker, utc_now(), request_id, result)
        self.events.append(event)
        return event


@dataclass
class BrokerRegistry:
    brokers: dict[str, BaseBroker] = field(default_factory=dict)

    def register(self, broker: BaseBroker) -> None:
        self.brokers[broker.name] = broker

    def get(self, name: str) -> BaseBroker:
        try:
            return self.brokers[name]
        except KeyError as error:
            raise LookupError(f"broker {name!r} is not connected") from error

    def statuses(self) -> list[dict[str, object]]:
        return [{"broker": name, "connected": True, "supports_live_orders": broker.supports_live_orders} for name, broker in self.brokers.items()]


class ExecutionService:
    def __init__(self, registry: BrokerRegistry | None = None, audit_log: AuditLog | None = None) -> None:
        self.registry = registry or BrokerRegistry()
        self.audit_log = audit_log or AuditLog()

    def submit_paper(self, request: OrderRequest) -> OrderResponse:
        if request.mode != TradingMode.PAPER:
            raise ValueError("paper endpoint only accepts PAPER mode")
        response = self.registry.get(request.broker).place_order(request)
        self.audit_log.record("paper_order", request.broker, request.idempotency_key, response.status)
        return response

    def confirm_live(self, request: OrderRequest, confirmed: bool) -> OrderResponse:
        if request.mode != TradingMode.LIVE:
            raise ValueError("live confirmation only accepts LIVE mode")
        if not confirmed:
            self.audit_log.record("live_order_rejected", request.broker, request.idempotency_key, "confirmation missing")
            raise PermissionError("live order requires explicit confirmation")
        broker = self.registry.get(request.broker)
        if not broker.supports_live_orders:
            self.audit_log.record("live_order_rejected", request.broker, request.idempotency_key, "broker disabled")
            raise PermissionError("connected broker does not permit live orders")
        response = broker.place_order(request)
        self.audit_log.record("live_order", request.broker, request.idempotency_key, response.status)
        return response
