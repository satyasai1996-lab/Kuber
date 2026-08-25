# Architecture

Kuber separates decision-making from orchestration. Each bot receives `WorkflowState` and returns a typed artifact. The `DevelopmentWorkflow` alone advances phases.

## Bot contracts

| Bot | Input | Output |
| --- | --- | --- |
| Planner | goal | `Plan` |
| Implementer | plan | proposed source files |
| Tester | source files | `TestReport` |
| Reviewer | code and test report | `ReviewReport` |
| Fixer | open findings | corrected source files |
| Validator | full state | `ValidationReport` |

The built-in bots are deterministic fixtures suitable for local testing. Production adapters should invoke an LLM or sandbox through the same interfaces, validate untrusted output before adding it to state, and preserve the audit event format.

## Guardrails

- Test failures and unresolved review findings block final validation.
- The workflow has an explicit `max_repairs` budget and fails closed when it is exhausted.
- Every phase transition is timestamped in the audit trail.
- Bots have no direct file-system or network authority; adapters should inject narrowly-scoped capabilities.
