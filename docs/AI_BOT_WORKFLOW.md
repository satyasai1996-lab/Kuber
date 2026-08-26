# Kuber Bot Architecture and Delivery Workflow

Kuber has two coordinated layers. Fourteen development bots build and validate
the product. Seven trading analysts produce market analysis at runtime. These
layers must not be confused.

## Development workflow

```text
Repository Analyst: INSPECT + REUSE/MODIFY/NEW evidence
  -> Project Manager and System Architect: PLAN + acceptance contracts
  -> Specialist engineers: IMPLEMENT
  -> Integration, QA and Trading Safety: TEST
  -> System Architect and Security Auditor: REVIEW
  -> Owning specialist engineers: FIX
  -> Integration, QA and Trading Safety: RETEST
  -> Security Auditor and Release Manager: FINAL VALIDATION
  -> Release Manager: RELEASE only after explicit user approval
```

The machine-readable definitions are in `project_bots/registry.json` and
`project_bots/workflow.json`. `src/kuber/devbots/` enforces ordering, ownership,
evidence and blocker rules.

## Runtime trading-analysis workflow

```text
Validated MarketIntelligence + GEXSnapshot
  -> seven independent analysts
     Technical | Fundamental | Options | News/Macro
     Sentiment | Sector Rotation | Risk
  -> schema validation
  -> scorecard and conflict detection
  -> Bull case -> Bear case -> rebuttals -> facilitator
  -> fund manager -> Risk Manager veto/approval
  -> conservative, neutral and aggressive plans
  -> paper/live execution gate
```

All runtime agents must reference one immutable market input version. The
Options Analyst interprets GEX in detail, but every analyst and the final Risk
Manager receive the same validated snapshot.

## Target boundary

`Android -> authenticated FastAPI REST/WebSocket -> authoritative Python market
and agent services -> risk/execution -> broker adapters`

Android shows freshness, reconnect state, analysis and confirmations. It does
not own authoritative GEX, Greeks, position sizing or order-risk logic.
