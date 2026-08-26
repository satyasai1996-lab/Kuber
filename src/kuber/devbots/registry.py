"""Load and validate the machine-readable Kuber bot registry."""

from __future__ import annotations

import json
from pathlib import Path

from .models import BotDefinition, Stage, StageContract

EXPECTED_BOTS = {
    "project_manager",
    "repository_analyst",
    "system_architect",
    "backend_engineer",
    "gex_engineer",
    "options_ai_engineer",
    "ai_orchestrator",
    "broker_engineer",
    "android_engineer",
    "integration_engineer",
    "qa_test_engineer",
    "trading_safety_engineer",
    "security_auditor",
    "release_manager",
}


def project_root() -> Path:
    return Path(__file__).resolve().parents[3]


class BotRegistry:
    def __init__(self, root: Path | None = None) -> None:
        self.root = root or project_root()
        self.bots = self._load_bots(self.root / "project_bots" / "registry.json")
        self.stages = self._load_stages(self.root / "project_bots" / "workflow.json")
        self.validate()

    @staticmethod
    def _load_bots(path: Path) -> dict[str, BotDefinition]:
        payload = json.loads(path.read_text(encoding="utf-8"))
        bots: dict[str, BotDefinition] = {}
        for item in payload["bots"]:
            definition = BotDefinition(
                bot_id=item["id"],
                name=item["name"],
                role=item["role"],
                owns=tuple(item["owns"]),
                outputs=tuple(item["outputs"]),
            )
            if definition.bot_id in bots:
                raise ValueError(f"duplicate bot id: {definition.bot_id}")
            bots[definition.bot_id] = definition
        return bots

    @staticmethod
    def _load_stages(path: Path) -> tuple[StageContract, ...]:
        payload = json.loads(path.read_text(encoding="utf-8"))
        return tuple(
            StageContract(
                stage=Stage(item["id"]),
                owners=tuple(item["owners"]),
                required_evidence=tuple(item["required_evidence"]),
            )
            for item in payload["stages"]
        )

    def validate(self) -> None:
        actual = set(self.bots)
        if actual != EXPECTED_BOTS:
            missing = sorted(EXPECTED_BOTS - actual)
            extra = sorted(actual - EXPECTED_BOTS)
            raise ValueError(f"invalid bot registry; missing={missing}, extra={extra}")
        if tuple(contract.stage for contract in self.stages) != tuple(Stage):
            raise ValueError("workflow stages must be complete and in required order")
        for contract in self.stages:
            unknown = set(contract.owners) - actual
            if unknown:
                raise ValueError(f"{contract.stage} has unknown owners: {sorted(unknown)}")
            if not contract.required_evidence:
                raise ValueError(f"{contract.stage} has no evidence gate")

    def bot(self, bot_id: str) -> BotDefinition:
        try:
            return self.bots[bot_id]
        except KeyError as exc:
            raise KeyError(f"unknown Kuber bot: {bot_id}") from exc

    def contract(self, stage: Stage) -> StageContract:
        return next(item for item in self.stages if item.stage == stage)
