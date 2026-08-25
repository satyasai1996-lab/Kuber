from __future__ import annotations

import argparse

from kuber.orchestration.workflow import DevelopmentWorkflow


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a Kuber development workflow")
    parser.add_argument("--goal", required=True)
    args = parser.parse_args()
    state = DevelopmentWorkflow().run(args.goal)
    print(f"phase={state.phase.value}; repairs={state.repairs}; validation={state.validation_report.passed if state.validation_report else False}")
    return 0 if state.phase.value == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
