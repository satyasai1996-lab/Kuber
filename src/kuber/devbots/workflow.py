"""Evidence-gated orchestration for the 14 Kuber development bots."""

from __future__ import annotations

from dataclasses import dataclass, field

from .models import GateResult, Handoff, Stage
from .registry import BotRegistry


class WorkflowViolation(ValueError):
    """Raised when a bot attempts to bypass ownership or a delivery gate."""


@dataclass
class DevelopmentWorkflow:
    work_item: str
    registry: BotRegistry = field(default_factory=BotRegistry)
    history: list[Handoff] = field(default_factory=list)

    @property
    def next_stage(self) -> Stage:
        return tuple(Stage)[len(self.history)]

    @property
    def complete(self) -> bool:
        return len(self.history) == len(Stage)

    def accept(self, handoff: Handoff, *, explicit_user_approval: bool = False) -> GateResult:
        if self.complete:
            raise WorkflowViolation("workflow is already released")
        if handoff.work_item != self.work_item:
            raise WorkflowViolation("handoff belongs to a different work item")
        expected = self.next_stage
        if handoff.stage != expected:
            raise WorkflowViolation(f"expected {expected.value}, got {handoff.stage.value}")
        contract = self.registry.contract(expected)
        if handoff.actor not in contract.owners:
            raise WorkflowViolation(f"{handoff.actor} does not own {expected.value}")
        missing = sorted(set(contract.required_evidence) - set(handoff.evidence))
        if missing:
            raise WorkflowViolation(f"{expected.value} missing evidence: {missing}")
        if handoff.blockers:
            raise WorkflowViolation(f"{expected.value} has unresolved blockers: {list(handoff.blockers)}")
        if expected is Stage.RELEASE and not explicit_user_approval:
            raise WorkflowViolation("release requires explicit user approval")
        self.history.append(handoff)
        return GateResult(True, expected, ("ownership verified", "evidence verified", "no blockers"))
