from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping


class Phase(str, Enum):
    PLANNING = "planning"
    IMPLEMENTATION = "implementation"
    TESTING = "testing"
    REVIEW = "review"
    FIXING = "fixing"
    RETESTING = "re-testing"
    FINAL_VALIDATION = "final_validation"
    COMPLETE = "complete"
    FAILED = "failed"


@dataclass(frozen=True)
class Plan:
    goal: str
    acceptance_criteria: tuple[str, ...]


@dataclass(frozen=True)
class TestReport:
    passed: bool
    failures: tuple[str, ...] = ()


@dataclass(frozen=True)
class ReviewReport:
    approved: bool
    findings: tuple[str, ...] = ()


@dataclass(frozen=True)
class ValidationReport:
    passed: bool
    reasons: tuple[str, ...] = ()


@dataclass
class WorkflowState:
    goal: str
    phase: Phase = Phase.PLANNING
    plan: Plan | None = None
    files: dict[str, str] = field(default_factory=dict)
    test_report: TestReport | None = None
    review_report: ReviewReport | None = None
    validation_report: ValidationReport | None = None
    repairs: int = 0
    audit_log: list[str] = field(default_factory=list)

    def snapshot_files(self) -> Mapping[str, str]:
        return dict(self.files)
