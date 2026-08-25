from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from kuber.bots.base import Fixer, Implementer, Planner, Reviewer, Tester, Validator
from kuber.bots.default import DeterministicFixer, DeterministicImplementer, DeterministicPlanner, DeterministicReviewer, DeterministicTester, FinalValidator
from kuber.models import Phase, WorkflowState


@dataclass(frozen=True)
class WorkflowDependencies:
    planner: Planner
    implementer: Implementer
    tester: Tester
    reviewer: Reviewer
    fixer: Fixer
    validator: Validator

    @classmethod
    def defaults(cls) -> "WorkflowDependencies":
        return cls(DeterministicPlanner(), DeterministicImplementer(), DeterministicTester(), DeterministicReviewer(), DeterministicFixer(), FinalValidator())


class DevelopmentWorkflow:
    def __init__(self, dependencies: WorkflowDependencies | None = None, max_repairs: int = 2) -> None:
        if max_repairs < 1:
            raise ValueError("max_repairs must be at least one")
        self.dependencies = dependencies or WorkflowDependencies.defaults()
        self.max_repairs = max_repairs

    def run(self, goal: str) -> WorkflowState:
        state = WorkflowState(goal=goal)
        self._move(state, Phase.PLANNING)
        state.plan = self.dependencies.planner.plan(goal)
        self._move(state, Phase.IMPLEMENTATION)
        state.files = self.dependencies.implementer.implement(state)
        self._evaluate_and_repair(state)
        if state.phase is Phase.FAILED:
            return state
        self._move(state, Phase.FINAL_VALIDATION)
        state.validation_report = self.dependencies.validator.validate(state)
        self._move(state, Phase.COMPLETE if state.validation_report.passed else Phase.FAILED)
        return state

    def _evaluate_and_repair(self, state: WorkflowState) -> None:
        while True:
            self._move(state, Phase.TESTING if state.repairs == 0 else Phase.RETESTING)
            state.test_report = self.dependencies.tester.test(state)
            self._move(state, Phase.REVIEW)
            state.review_report = self.dependencies.reviewer.review(state)
            if state.test_report.passed and state.review_report.approved:
                return
            if state.repairs >= self.max_repairs:
                self._move(state, Phase.FAILED)
                return
            self._move(state, Phase.FIXING)
            state.files.update(self.dependencies.fixer.fix(state))
            state.repairs += 1

    @staticmethod
    def _move(state: WorkflowState, phase: Phase) -> None:
        state.phase = phase
        stamp = datetime.now(timezone.utc).isoformat()
        state.audit_log.append(f"{stamp} {phase.value}")
