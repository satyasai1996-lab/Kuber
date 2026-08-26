# Kuber Server Options / GEX Evidence Audit

Audit date: 2026-08-26  
Reference: `hopit-ai/india-trade-cli` commit
`e62ac86d4a182f6df0f785c4620a527d753b4517`  
Kuber checkout inspected: `work/publish`

This is a read-only implementation audit. It does not establish a real broker
connection or authorise live trading. The required authority boundary is:

```text
Android DTO/UI
  -> authenticated FastAPI REST/WebSocket
  -> server-normalized option chain
  -> server IV/Greeks
  -> one immutable, versioned GEX snapshot
  -> agents/risk/execution
```

Android may render the server payload but must not independently produce an
authoritative IV, Gamma, PCR, anomaly or GEX value.

## Executive findings

- The upstream dealer-short GEX equation is reusable as the Kuber behavioural
  convention, subject to instrument-specific validation.
- Kuber Python Gamma Flip does **not** match upstream edge-case semantics.
- Kuber Python/API currently use percentage IV values while the Kotlin model
  explicitly uses decimal IV. The API does not declare the unit.
- The current server option-chain builder is Zerodha NFO-only and has only a
  narrow NIFTY fixture.
- SENSEX/BFO and MCX option chains are not implemented or tested in Kuber.
- MCX options require a corresponding futures reference and a Black-76-aware
  model; the existing index/spot Black-Scholes path is insufficient.
- The Kotlin calculations and deterministic tests are useful cross-language
  regression evidence, but they are not production authority in the corrected
  client/server architecture.
- `docs/MOBILE_LOCAL_ARCHITECTURE.md` and the current
  `docs/LIVE_MARKET_REUSE_MODIFY_NEW_AUDIT.md` describe a conflicting
  mobile-local authority and must not override the server-authoritative workflow.

## Exact source evidence

### Upstream India Trade CLI

| Capability | Exact evidence |
| --- | --- |
| Normalized option row | `work/india-trade-cli-upstream/brokers/base.py:86-103` (`OptionsContract`) |
| Broker-first chain and NSE fallback | `work/india-trade-cli-upstream/market/options.py:23-53` |
| Chain pivot and zero-filled missing values | `work/india-trade-cli-upstream/market/options.py:66-112` |
| OI PCR | `work/india-trade-cli-upstream/market/options.py:126-136` |
| OI walls and OI-change profile | `work/india-trade-cli-upstream/market/oi_profile.py:27-104` |
| Black-Scholes Greeks and IV | `work/india-trade-cli-upstream/analysis/options.py:133-223` |
| IV smile and skew | `work/india-trade-cli-upstream/analysis/volatility_surface.py:29-107` |
| GEX equation | `work/india-trade-cli-upstream/analysis/gex.py:34-50` |
| Positive-to-nonpositive Gamma Flip | `work/india-trade-cli-upstream/analysis/gex.py:53-77` |
| GEX aggregation and regime | `work/india-trade-cli-upstream/analysis/gex.py:91-160` |
| Zerodha NFO chain | `work/india-trade-cli-upstream/brokers/zerodha.py:190-240` |
| Angel One NSE-web fallback | `work/india-trade-cli-upstream/brokers/angelone.py:359-415` |
| Fyers limited option-chain mapping | `work/india-trade-cli-upstream/brokers/fyers.py:502-561` |
| NSE scraper index list/parser | `work/india-trade-cli-upstream/market/nse_scraper.py:44-60,119-190` |

### Kuber authoritative Python candidate

| Capability | Exact evidence |
| --- | --- |
| Python option/GEX/market models | `src/kuber/models.py:41-111` |
| Provider normalizer | `src/kuber/market/normalizer.py:11-51` |
| IV bisection and Gamma | `src/kuber/market/option_chain.py:25-62` |
| Zerodha NFO chain builder | `src/kuber/market/option_chain.py:72-150` |
| Python GEX and Gamma Flip | `src/kuber/market/gex.py:12-76` |
| Shared intelligence publication | `src/kuber/market/intelligence.py:10-29` |
| API option input | `src/kuber/api/app.py:52-67` |
| Broker refresh into shared intelligence | `src/kuber/api/app.py:169-180` |
| Separate GEX/chain/IV-smile routes | `src/kuber/api/app.py:310-378` |
| Zerodha reference alias and chain gateway | `src/kuber/brokers/zerodha.py:45-77` |

### Kotlin prototype/regression reference

| Capability | Exact evidence |
| --- | --- |
| Decimal-IV contract and immutable market version | `android/core-model/src/main/java/ai/kuber/core/model/market/MarketModels.kt` |
| Versioned GEX DTO | `android/core-model/src/main/java/ai/kuber/core/model/market/GexModels.kt` |
| Options/PCR/IV/anomaly DTOs | `android/core-model/src/main/java/ai/kuber/core/model/market/OptionsModels.kt` |
| Black-Scholes implementation | `android/core-market/src/main/java/ai/kuber/core/market/options/BlackScholes.kt` |
| Validation and freshness checks | `android/core-market/src/main/java/ai/kuber/core/market/options/OptionChainValidator.kt` |
| Options analytics | `android/core-market/src/main/java/ai/kuber/core/market/options/OptionsAnalyticsCalculator.kt` |
| Upstream-compatible GEX port | `android/core-market/src/main/java/ai/kuber/core/market/gex/GexCalculator.kt` |
| One-version builder | `android/core-market/src/main/java/ai/kuber/core/market/intelligence/MarketIntelligenceBuilder.kt` |
| Direct NFO-only broker prototype | `android/core-broker/src/main/java/ai/kuber/core/broker/zerodha/ZerodhaBroker.kt:110-190` |

## REUSE / MODIFY / NEW classification

| Capability | Class | Evidence-backed disposition |
| --- | --- | --- |
| Dealer-short GEX equation | **REUSE, narrowly** | Preserve `OI * gamma * reference_price * lot_size * 100`, CE positive and PE negative, as the project parity convention. It is not yet calibrated for MCX contract units. |
| Gamma Flip rule | **REUSE specification / MODIFY Python** | Preserve only the first `previous > 0 && current <= 0` crossing. Correct Python before claiming parity. |
| GEX aggregation | **MODIFY** | Use each contract's lot size, aggregate rather than overwrite same-strike rows, partition/identify expiries, validate all inputs and never silently skip failed rows. |
| Fixed regime threshold `+/-50` | **MODIFY** | Retain for parity fixtures only. Version/configure it before it affects multi-instrument risk because its magnitude is scale-dependent. |
| Black-Scholes equations | **REUSE mathematics / MODIFY implementation** | Keep server-side pricing/IV/Gamma with exact valuation timestamp, declared units and explicit failure states. |
| Broker instrument-master plus quote pattern | **MODIFY** | Preserve the pattern, but make exchange/reference mapping provider-aware, cache/version the daily master and fail closed on absent quote keys. |
| Normalized option contract | **MODIFY** | Add provider identifiers, exchange/segment, exchange timestamps, tick size, nullable market fields and calculation provenance. |
| OI PCR | **MODIFY** | Preserve `sum(PE OI) / sum(CE OI)` per expiry. Missing side or zero denominator is unavailable/null, not `0.0`. |
| Volume PCR | **NEW** | Present in the Kotlin prototype, not upstream. Add a versioned server field and fixtures. |
| IV smile | **MODIFY** | Preserve expiry/strike CE/PE values with nullable missing sides. Never zero-fill absent IV. |
| Aggregate IV skew/anomaly thresholds | **NEW semantics** | Android's average put-minus-call IV differs from upstream OTM-put/ATM/OTM-call skew. Define and version the server methodology. |
| OI walls | **MODIFY** | Partition by expiry, require positive OI and define deterministic tie-breaking. |
| OI-change classification | **MODIFY/NEW** | Upstream four-quadrant classification requires both price and OI direction. Sign-only OI delta is a separate signal, not a long/short buildup conclusion. |
| Zerodha NIFTY/NFO | **MODIFY** | Partial implementation only; add timestamps, OI-change provenance, complete validation, captured provider fixtures and controlled acceptance evidence. |
| SENSEX/BFO | **NEW** | Implement BSE reference quote plus BFO instrument master and option quotes independently. |
| MCX option analytics | **NEW** | Implement option-to-underlying-future linkage, Black-76 inputs, contract-unit validation and provider fixtures. |
| Atomic mobile API payload | **NEW** | Publish chain, analytics and GEX from one snapshot/version rather than unrelated endpoint-specific shapes. |
| Kotlin calculation code | **REUSE as tests/DTO guidance only** | Production Android must deserialize and display the authoritative server result, not calculate a competing value. |

## Confirmed Gamma Flip incompatibility

The upstream function searches only a positive-to-nonpositive crossing:

```python
if prev_gex > 0 and curr_gex <= 0:
```

Kuber Python instead treats either-sign multiplication below zero as a flip and
also special-cases a zero previous row. The resulting truth table is:

| Adjacent strikes | Upstream | Kuber Python | Required server result |
| --- | ---: | ---: | ---: |
| `+100 @ 100 -> -300 @ 200` | `125` | `125` | `125` |
| `-100 @ 100 -> +300 @ 200` | `None` | `125` | `None` |
| `+100 @ 100 -> 0 @ 200` | `200` | `None` | `200` |
| `0 @ 100 -> -100 @ 200` | `None` | `100` | `None` |

Therefore `src/kuber/market/gex.py` is not an exact reuse until corrected and
covered by the complete truth table, multiple-crossing and no-crossing tests.

## IV/Greeks defects and limitations

### Unit mismatch

- `src/kuber/market/option_chain.py:133-143` calculates decimal IV and stores
  `iv * 100` in `OptionContract.implied_volatility`.
- API fixtures also use values such as `14.1` and `15.6`.
- Kotlin `OptionContract` explicitly states `0.20 = 20%` and validates IV in
  `(0, 5]`.

The server contract must standardize on decimal IV, include the unit in schema
documentation and reject an accidental percentage/decimal mix.

### Pricing/model gaps

- Python's call-price initial intrinsic check is not the fully discounted
  Black-Scholes lower bound; an invalid price can collapse toward minimum IV.
- The Python model permits zero IV/Gamma, allowing unavailable Greeks to become
  zero GEX rather than an explicit unavailable calculation.
- Upstream uses local `date.today()` with a minimum of one day. The Python
  builder uses integer calendar days plus one. The authoritative calculation
  must use the snapshot valuation timestamp and exchange expiry time.
- The existing spot Black-Scholes path has no declared dividend/carry input.
- MCX options on futures require the corresponding futures reference and
  Black-76 validation; an NSE-style spot input is invalid for that product.

## Required server live-data schema

### Snapshot envelope

Every result consumed by agents or Android must include:

```text
schema_version
snapshot_id
input_version
canonical_symbol
instrument_family
provider
source
captured_at
freshness
expiry_scope
reference_quote
option_contracts
options_analytics
gex_snapshot
data_quality
unavailable_fields
```

Chain, analytics and GEX must carry the same snapshot ID, input version,
timestamp and source through REST responses and WebSocket replay/reconnect.

### Instrument metadata

Required provider-master fields:

```text
instrument_token
exchange_token
exchange
segment
tradingsymbol
name / canonical_underlying
instrument_type (CE | PE | FUT | index)
expiry
strike
tick_size
lot_size
```

Persist `exchange + tradingsymbol` as the stable provider key. Derivative
instrument tokens may be reused after expiry.

### Quote fields

Required or explicitly nullable fields:

```text
requested_instrument_key
instrument_token
exchange_timestamp
last_trade_time
received_at
last_price
volume
open_interest
oi_day_high
oi_day_low
average_price
ohlc
best_bid / best_ask (when available)
```

The provider can omit a requested quote key. Absence must be represented as a
data-quality failure/unavailable contract, not a synthetic zero row.

### Normalized option and derived fields

Each normalized contract requires:

```text
provider + exchange + segment + tradingsymbol + tokens
underlying + option_type + expiry + strike
lot_size + tick_size
last_price + volume + open_interest (nullable provider fields)
open_interest_change (nullable)
oi_change_baseline_timestamp/source (when derived)
implied_volatility_decimal (nullable)
gamma (nullable)
pricing_model
risk_free_rate / carry or futures reference
valuation_timestamp
calculation_status / unavailable_reason
```

Kite documents OI, OI day high and OI day low, but not a canonical
`oi_day_change` response field. OI change must therefore remain nullable or be
derived from a timestamped prior-close OI baseline.

Official reference:
[Kite market quotes and instruments](https://kite.trade/docs/connect/v3/market-quotes/).

## Instrument-family requirements

### NIFTY

- Canonical symbol: `NIFTY`.
- Reference quote: `NSE:NIFTY 50`.
- Derivative exchange: `NFO`.
- Filter the daily master by `exchange=NFO`, `name=NIFTY` and
  `instrument_type in {CE, PE}`.
- Derive lot size, strike set and valid expiries from the daily master; do not
  use static UI values.
- Partition analytics by the selected expiry or explicitly label a
  multi-expiry aggregate.

Current Kuber support is partial only.

### SENSEX / BFO

- Canonical symbol: `SENSEX`.
- Reference quote: `BSE:SENSEX`.
- Derivative exchange: `BFO`.
- Contract quote keys: `BFO:<tradingsymbol>`.
- Filter the BFO master for current SENSEX CE/PE rows.
- Derive expiry, strike, lot size and tick size from the provider master.

Current Python and Kotlin broker paths default unknown symbols to NSE and load
NFO only, so SENSEX/BFO is not supported as implemented. Kite's current Java
constants explicitly include BFO:
[official exchange constants](https://kite.trade/docs/javakiteconnect/v3/constant-values.html).

### MCX

- Canonical commodity symbol plus exact provider contract identity is required.
- Exchange: `MCX`; quote keys use `MCX:<tradingsymbol>`.
- Link every option expiry to its corresponding underlying futures contract.
- Use that futures price/reference timestamp for moneyness, IV and Gamma.
- Obtain lot/contract units, tick size, expiry and settlement relation from the
  current provider master/contract specification.
- Validate the pricing method and GEX multiplier per product before enabling
  the field; otherwise report IV/GEX unavailable.

MCX confirms that commodity options use the corresponding commodity futures
contract as underlying and that Black-76 applies to options on futures:
[MCX market operations FAQ](https://beta.mcxindia.com/en/faq/market-operations).

No current Kuber implementation or test satisfies these requirements.

## Mandatory regression fixtures

### 1. Exact GEX sign and magnitude

For `OI=100`, `gamma=.02`, `reference=22000`, `lot=25`:

```text
CE = +110,000,000
PE = -110,000,000
```

### 2. Gamma Flip truth table

Test all four rows in the incompatibility table, plus:

- multiple crossings return only the first positive-to-nonpositive crossing;
- all-positive, all-negative and empty/no-crossing input return unavailable;
- input ordering is deterministic and validated;
- interpolation precision/rounding is defined at the API boundary.

### 3. Multi-expiry/per-contract-lot aggregation

Fixture:

- expiry 1: CE OI `10` at strike `100`; PE OI `5` at strike `110`;
- expiry 2: CE OI `5` at strike `100`; PE OI `20` at strike `110`;
- `gamma=.01`, reference `100`, lot `1`.

Expected:

```text
strike 100 net GEX = +1500
strike 110 net GEX = -2500
expiry 1 net GEX   = +500
expiry 2 net GEX   = -1500
total GEX          = -1000
Gamma Flip         = 103.75
call wall          = 100
put wall           = 110
```

Add a variant with different lot sizes per contract to prevent reintroduction
of upstream's `chain[0].lot_size` defect.

### 4. Regime boundaries

```text
+50.00  -> NEUTRAL
-50.00  -> NEUTRAL
+50.01  -> POSITIVE
-50.01  -> NEGATIVE
```

### 5. Black-Scholes golden values

For `S=100`, `K=100`, `T=.5`, `r=.065`, `sigma=.20`:

```text
CE price = 7.291520438956269
PE price = 4.093765422086875
gamma    = 0.026963977606737702
recovered IV = 0.20
```

Also test call/put arbitrage bounds, near-expiry behavior, invalid prices,
non-finite inputs, valuation timestamp and percentage-to-decimal rejection.

### 6. PCR / IV / OI analytics fixture

Use the deterministic contract set:

- CE OI totals `400`; PE OI totals `300` -> OI PCR `.75`;
- CE volume `200`; PE volume `150` -> volume PCR `.75`;
- average CE IV `.21`; average PE IV `.25`; put-minus-call skew `.04`;
- maximum call-OI strike `110`; maximum put-OI strike `100`.

Missing side, zero call denominator and absent provider OI change must return
explicit unavailable/null fields, never synthetic zero analytics.

### 7. Provider contract fixtures

Capture and sanitize independent fixtures for:

- NIFTY: NSE index reference plus NFO master/quotes;
- SENSEX: BSE index reference plus BFO master/quotes;
- every enabled MCX commodity: option plus linked underlying future;
- expired rows, duplicate rows and mixed underlyings/exchanges;
- missing quote response keys and partial CE/PE coverage;
- missing/stale/future exchange timestamps;
- dynamic lot-size and tick-size changes;
- nullable OI and prior-close OI-change provenance;
- quote batching beyond the provider's 500-instrument full-quote limit;
- master refresh and derivative-token reuse across expiry.

### 8. Atomic API and reconnect fixtures

Assert that option chain, options analytics, GEX, agent inputs and WebSocket
latest-value replay all carry exactly the same:

```text
snapshot_id
input_version
captured_at
source
freshness
```

Stale data, incomplete calculation coverage or a newer market version must
fail closed before risk/execution.

## Current test evidence and limitations

- Targeted Kuber Python tests passed during this audit:
  `test_option_chain.py` and `test_market_intelligence.py` -> **3 passed**.
- They cover one positive NIFTY/NFO chain and one shared-GEX publication only.
- They do not assert exact GEX magnitude, Gamma Flip edge semantics, PCR,
  IV-unit consistency, OI change, BFO, MCX, stale data or provider failure.
- The existing Kotlin core tests are stronger deterministic seeds but are not
  evidence that the authoritative server works.
- Upstream tests assert GEX sign and a broad flip range, not the complete truth
  table or exact aggregation behavior. Several network-facing tests assert only
  that a function returns a container.
- The upstream baseline suite was not established by this audit; importing it
  in the Kuber test environment lacked its `rich` dependency.
- No controlled live broker fixture/session or BFO/MCX acceptance record was
  present. No live provider or order was exercised by this audit.

## Release gate

Until the server schemas, cross-language golden fixtures and provider contract
tests above pass, Kuber may claim only partial Zerodha NIFTY/NFO option-chain
work. SENSEX/BFO and MCX option analytics must be exposed as unavailable, and
Android-local calculations must not be used as authoritative trading inputs.
