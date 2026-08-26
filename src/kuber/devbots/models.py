"""Data contracts shared by every Kuber development bot."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class Stage(StrEnum):
    INSPECT = "inspect"
    PLAN = "plan"
    IMPLEMENT = "implement"
    TEST = "test"
    REVIEW = "review"
    FIX = "fix"
    RETEST = "retest"
    FINAL_VALIDATE = "final_validate"
    RELEASE = "release"


@dataclass(frozen=True)
class BotDefinition:
    bot_id: str
    name: str
    role: str
    owns: tuple[str, ...]
    outputs: tuple[str, ...]

    def __post_init__(self) -> None:
        if not self.bot_id or not self.name or not self.role:
            raise ValueError("bot id, name and role are required")
        if not self.owns or not self.outputs:
            raise ValueError(f"{self.bot_id} needs ownership and output contracts")


@dataclass(frozen=True)
class StageContract:
    stage: Stage
    owners: tuple[str, ...]
    required_evidence: tuple[str, ...]


@dataclass(frozen=True)
class Handoff:
    work_item: str
    stage: Stage
    actor: str
    artifacts: tuple[str, ...]
    evidence: tuple[str, ...]
    blockers: tuple[str, ...] = ()
    notes: str = ""

    def __post_init__(self) -> None:
        if not self.work_item.strip():
            raise ValueError("handoff work_item is required")
        if not self.actor.strip():
            raise ValueError("handoff actor is required")
        if not self.artifacts:
            raise ValueError("handoff must identify at least one artifact")
        if not self.evidence:
            raise ValueError("handoff must include evidence")


@dataclass(frozen=True)
class GateResult:
    accepted: bool
    stage: Stage
    reasons: tuple[str, ...]
