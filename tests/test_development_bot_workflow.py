import pytest

from kuber.devbots import BotRegistry, DevelopmentWorkflow, Handoff, Stage, WorkflowViolation


def handoff(work_item: str, stage: Stage, actor: str, evidence: tuple[str, ...]) -> Handoff:
    return Handoff(
        work_item=work_item,
        stage=stage,
        actor=actor,
        artifacts=(f"artifacts/{stage.value}.json",),
        evidence=evidence,
    )


def test_registry_contains_all_fourteen_supplied_bots_and_required_stage_order():
    registry = BotRegistry()
    assert len(registry.bots) == 14
    assert tuple(item.stage for item in registry.stages) == tuple(Stage)
    assert registry.bot("release_manager").name == "Release Manager"


def test_workflow_rejects_skipped_stage():
    workflow = DevelopmentWorkflow("mobile-conversion")
    with pytest.raises(WorkflowViolation, match="expected inspect"):
        workflow.accept(
            handoff(
                "mobile-conversion",
                Stage.IMPLEMENT,
                "android_engineer",
                ("changed_artifacts", "implementation_tests"),
            )
        )


def test_workflow_rejects_wrong_owner_and_missing_evidence():
    workflow = DevelopmentWorkflow("mobile-conversion")
    with pytest.raises(WorkflowViolation, match="does not own inspect"):
        workflow.accept(
            handoff(
                "mobile-conversion",
                Stage.INSPECT,
                "android_engineer",
                ("repository_map", "reuse_modify_new_report"),
            )
        )
    with pytest.raises(WorkflowViolation, match="missing evidence"):
        workflow.accept(
            handoff("mobile-conversion", Stage.INSPECT, "repository_analyst", ("repository_map",))
        )


def test_full_pipeline_requires_review_fix_retest_and_explicit_release_approval():
    workflow = DevelopmentWorkflow("mobile-conversion")
    sequence = (
        (Stage.INSPECT, "repository_analyst", ("repository_map", "reuse_modify_new_report")),
        (Stage.PLAN, "system_architect", ("architecture_decision", "acceptance_criteria")),
        (Stage.IMPLEMENT, "backend_engineer", ("changed_artifacts", "implementation_tests")),
        (Stage.TEST, "qa_test_engineer", ("test_report", "safety_report")),
        (Stage.REVIEW, "security_auditor", ("review_findings", "security_findings")),
        (Stage.FIX, "android_engineer", ("fix_report",)),
        (Stage.RETEST, "integration_engineer", ("retest_report", "tests_green")),
        (
            Stage.FINAL_VALIDATE,
            "release_manager",
            ("security_review", "paper_acceptance", "change_log", "rollback_plan"),
        ),
    )
    for stage, actor, evidence in sequence:
        assert workflow.accept(handoff("mobile-conversion", stage, actor, evidence)).accepted

    release = handoff(
        "mobile-conversion", Stage.RELEASE, "release_manager", ("explicit_user_approval",)
    )
    with pytest.raises(WorkflowViolation, match="explicit user approval"):
        workflow.accept(release)
    assert workflow.accept(release, explicit_user_approval=True).accepted
    assert workflow.complete


def test_unresolved_blocker_stops_handoff():
    workflow = DevelopmentWorkflow("mobile-conversion")
    blocked = Handoff(
        work_item="mobile-conversion",
        stage=Stage.INSPECT,
        actor="repository_analyst",
        artifacts=("audit.md",),
        evidence=("repository_map", "reuse_modify_new_report"),
        blockers=("source commit is unavailable",),
    )
    with pytest.raises(WorkflowViolation, match="unresolved blockers"):
        workflow.accept(blocked)
