from __future__ import annotations

from typing import Protocol

from kuber.models import Plan, ReviewReport, TestReport, ValidationReport, WorkflowState


class Planner(Protocol):
    def plan(self, goal: str) -> Plan: ...


class Implementer(Protocol):
    def implement(self, state: WorkflowState) -> dict[str, str]: ...


class Tester(Protocol):
    def test(self, state: WorkflowState) -> TestReport: ...


class Reviewer(Protocol):
    def review(self, state: WorkflowState) -> ReviewReport: ...


class Fixer(Protocol):
    def fix(self, state: WorkflowState) -> dict[str, str]: ...


class Validator(Protocol):
    def validate(self, state: WorkflowState) -> ValidationReport: ...
