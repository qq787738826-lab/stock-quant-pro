# V1 Trade Calendar Forward Increment Fix

## Scope

`MAINBOARD_TRADE_CAL_FORWARD_INCREMENT` is the only formal operation added by
this repair. It extends the existing SSE and SZSE `trading_calendar_facts_v1`
chains after their current common maximum date. It does not change
`TRADE_CAL_BACKFILL`, market-price synchronization, selection, Shadow, Agent,
Paper, or model execution.

## Frozen contract

- Read the current SSE and SZSE maximum `cal_date` as-of execution time and
  require both facts to have valid Provider/PIT lineage and the same date.
- Compute `startDate = currentMaxCalDate + 1 natural day` inside the Runner.
  The request contains only the authorized `target.end.date`; callers cannot
  supply or move the start boundary.
- If `target.end.date <= currentMaxCalDate`, return `SUCCEEDED/NO_OP` with zero
  Provider calls and zero writes.
- Otherwise request only `trade_cal(exchange=SSE)` and
  `trade_cal(exchange=SZSE)`. Each exchange has one base call. The existing
  no-response transport recovery contract permits at most two additional
  attempts globally; the hard maximum remains four calls.
- Fetch and validate both exchange responses before persistence. Persist both
  responses in one dedicated transaction, so a one-sided Provider failure
  leaves both formal maxima unchanged.
- Require every natural date from `startDate` through `target.end.date`, no
  physical duplicate, both maxima equal to the target, and a determinable
  common Provider-open calendar and latest completed common open date.
- Preserve Provider `cal_date`, `is_open`, and `pretrade_date` in raw payloads,
  along with true `knownAt`, `firstObservedAt`, source identity, natural key,
  and capture batch lineage. Existing observations are append-only and cannot
  be updated, deleted, or re-fetched by this operation.
- `stock_basic`, `daily`, `adj_factor`, Selection, Shadow, Agent, Paper, and
  Bailian are forbidden and must remain zero.

## Broker and budget

The fixed Broker request reserves four Tushare calls before claim and is
accepted only when the authoritative monthly ledger plus four does not exceed
the configured limit. Request publication remains unique and atomic; a
claimed or failed request is never retried or reused. The old failed request
`SQHB_20260917T113826Z_7B5BA2973C9A` is immutable.

Deployment creates a dedicated Runner artifact with Start-Class
`TushareMainboardTradeCalendarForwardIncrementManualRunner`. Deployment does
not create a real request; a later real increment requires a separate explicit
authorization and new request ID.
