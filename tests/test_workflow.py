import unittest

from kuber.models import Phase
from kuber.orchestration.workflow import DevelopmentWorkflow


class WorkflowTests(unittest.TestCase):
    def test_full_workflow_repairs_then_completes(self) -> None:
        state = DevelopmentWorkflow().run("Create a greeting function")
        self.assertEqual(state.phase, Phase.COMPLETE)
        self.assertEqual(state.repairs, 1)
        self.assertTrue(state.test_report.passed)
        self.assertTrue(state.review_report.approved)
        self.assertTrue(state.validation_report.passed)
        phases = " ".join(state.audit_log)
        for expected in ("planning", "implementation", "testing", "review", "fixing", "re-testing", "final_validation", "complete"):
            self.assertIn(expected, phases)

    def test_repair_budget_fails_closed(self) -> None:
        class NeverFix:
            def fix(self, state):
                return state.files
        workflow = DevelopmentWorkflow(max_repairs=1)
        workflow.dependencies = workflow.dependencies.__class__(
            workflow.dependencies.planner, workflow.dependencies.implementer, workflow.dependencies.tester,
            workflow.dependencies.reviewer, NeverFix(), workflow.dependencies.validator
        )
        state = workflow.run("x")
        self.assertEqual(state.phase, Phase.FAILED)
