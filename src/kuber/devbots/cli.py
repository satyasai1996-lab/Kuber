"""Small command-line validator for Kuber development-bot contracts."""

from __future__ import annotations

import argparse

from .registry import BotRegistry


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate or inspect Kuber development bots")
    parser.add_argument("command", choices=("validate", "show"))
    args = parser.parse_args()
    registry = BotRegistry()
    if args.command == "validate":
        print(f"OK: {len(registry.bots)} bots and {len(registry.stages)} gated stages")
        return 0
    for contract in registry.stages:
        print(f"{contract.stage.value}: {', '.join(contract.owners)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
