import unittest

from kuber.bots.default import DeterministicFixer, DeterministicPlanner, DeterministicTester, FinalValidator
from kuber.models import Phase, WorkflowState


class BotTests(unittest.TestCase):
    def test_planner_defines_acceptance_criteria(self) -> None:
        plan = DeterministicPlanner().plan("build greeting")
        self.assertEqual(plan.goal, "build greeting")
        self.assertGreaterEqual(len(plan.acceptance_criteria), 2)

    def test_tester_fails_initial_draft_and_fixer_repairs_it(self) -> None:
        state = WorkflowState(goal="x", files={"app.py": "def greet(name: str) -> str:\n return 'Hello'\n"})
        self.assertFalse(DeterministicTester().test(state).passed)
        state.files.update(DeterministicFixer().fix(state))
        self.assertTrue(DeterministicTester().test(state).passed)

    def test_validator_blocks_incomplete_workflow(self) -> None:
        state = WorkflowState(goal="x", phase=Phase.TESTING)
        report = FinalValidator().validate(state)
        self.assertFalse(report.passed)
        self.assertIn("tests have not passed", report.reasons)
