package com.stockquant.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class BacktestModels {
    private BacktestModels() {}

    public record Request(BigDecimal initialCapital, int maxHoldingDays, BigDecimal stopLossPct,
                          BigDecimal takeProfitPct, BigDecimal trailingStopPct,
                          BigDecimal commissionRate, BigDecimal stampDutyRate) {}

    public record Trade(LocalDate entryDate, LocalDate exitDate, BigDecimal entryPrice,
                        BigDecimal exitPrice, int quantity, BigDecimal pnl, BigDecimal returnPct,
                        String exitReason) {}

    public record Result(BigDecimal initialCapital, BigDecimal finalCapital, BigDecimal totalReturn,
                         BigDecimal maxDrawdown, BigDecimal winRate, BigDecimal profitLossRatio,
                         int tradeCount, List<Trade> trades) {}
}
