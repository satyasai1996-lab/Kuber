# Kuber Development Bots

This directory is the executable contract for the 14 bots supplied with the
India Trade AI Android project. It does not contain the seven market analysts;
those live under `src/kuber/agents/` and operate only on a validated shared
market snapshot.

## Bot groups

| Gate | Bots |
| --- | --- |
| Inspect and plan | Project Manager, Repository Analyst, System Architect |
| Implement | Backend, GEX, Options AI, AI Orchestrator, Broker, Android |
| Integrate and test | Integration, QA/Test, Trading Safety |
| Review and release | Security Auditor, Release Manager |

`registry.json` is the machine-readable ownership registry. `workflow.json`
defines the permitted stage order, owners and evidence required at each gate.
The Python implementation in `src/kuber/devbots/` validates both contracts and
enforces handoffs.

The Release Manager is a gate, not an automatic deployer. Live trading remains
disabled until explicit user approval is recorded after all evidence passes.
