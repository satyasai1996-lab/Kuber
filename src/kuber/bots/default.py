"""Deterministic reference bots used for an executable workflow baseline."""
from __future__ import annotations

from kuber.models import Plan, ReviewReport, TestReport, ValidationReport, WorkflowState


class DeterministicPlanner:
    def plan(self, goal: str) -> Plan:
        return Plan(goal=goal, acceptance_criteria=("Expose a greet(name) function", "Return a personalized greeting"))


class DeterministicImplementer:
    def implement(self, state: WorkflowState) -> dict[str, str]:
        # Deliberately incomplete first draft validates the repair loop end-to-end.
        return {"app.py": "def greet(name: str) -> str:\n    return 'Hello'\n"}


class DeterministicTester:
    def test(self, state: WorkflowState) -> TestReport:
        source = state.files.get("app.py", "")
        if "f\"Hello, {name}!\"" not in source:
            return TestReport(False, ("greet(name) must return a personalized greeting",))
        return TestReport(True)


class DeterministicReviewer:
    def review(self, state: WorkflowState) -> ReviewReport:
        source = state.files.get("app.py", "")
        findings = () if 'name: str' in source and '-> str' in source else ("Function must include type annotations",)
        return ReviewReport(not findings, findings)


class DeterministicFixer:
    def fix(self, state: WorkflowState) -> dict[str, str]:
        return {"app.py": "def greet(name: str) -> str:\n    return f\"Hello, {name}!\"\n"}


class FinalValidator:
    def validate(self, state: WorkflowState) -> ValidationReport:
        reasons: list[str] = []
        if not state.test_report or not state.test_report.passed:
            reasons.append("tests have not passed")
        if not state.review_report or not state.review_report.approved:
            reasons.append("review findings remain")
        return ValidationReport(not reasons, tuple(reasons))
