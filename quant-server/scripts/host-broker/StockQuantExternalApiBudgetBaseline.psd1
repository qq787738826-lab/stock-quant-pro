@{
    SchemaVersion = 'STOCK_QUANT_EXTERNAL_API_BUDGET_BASELINE_V1'
    Months = @{
        '2026-08' = @{
            # M3 predates the monthly ledger.  Account its first tranche at
            # the full approved CNY 5.00 because early failure telemetry was
            # incomplete, then add the independently reconciled second
            # tranche CNY 1.777600000000.  M4 usage is discovered from
            # immutable Broker request/results and must not be duplicated.
            ProjectNonShadowCostCny = '6.777600000000'
        }
    }
}
