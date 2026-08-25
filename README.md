# Kuber

Kuber is a small, dependency-free framework for coordinating AI development agents through a governed delivery loop:

`planning -> implementation -> testing -> review -> fixing -> re-testing -> final validation`

Each bot has one responsibility and returns typed artifacts into a shared `WorkflowState`. The orchestrator owns control flow, guardrails, retry limits, and the immutable audit trail; bots never call each other directly.

## Quick start

Requires Python 3.11+.

```bash
python -m unittest discover -s tests -v
python -m kuber.cli --goal "Create a greeting function"
```

The built-in deterministic bots demonstrate the complete loop. Replace individual bots with an LLM-backed implementation by implementing the corresponding protocol from `kuber.bots.base`.

## Layout

- `src/kuber/bots/`: independently testable planning, implementation, testing, review, fixing, and validation bots.
- `src/kuber/orchestration/`: workflow state and the coordinator.
- `tests/`: unit and end-to-end workflow tests.
- `docs/architecture.md`: contracts, extension points, and safety rules.

## Workflow guarantees

The coordinator refuses final validation until tests pass and review findings are resolved. It records every state transition and fails closed when the configured repair budget is exhausted.
