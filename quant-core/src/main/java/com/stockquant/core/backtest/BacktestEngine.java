package com.stockquant.core.backtest;

import com.stockquant.core.domain.Bar;
import com.stockquant.core.domain.BacktestModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class BacktestEngine {
    public Result run(List<Bar> bars, Request req) {
        if (bars == null || bars.size() < 30) throw new IllegalArgumentException("回测至少需要30条K线");
        BigDecimal capital = req.initialCapital();
        BigDecimal peak = capital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        List<Trade> trades = new ArrayList<>();

        for (int i = 20; i < bars.size() - 2; i += Math.max(2, req.maxHoldingDays())) {
            Bar signal = bars.get(i);
            BigDecimal avg20 = bars.subList(i - 19, i + 1).stream().map(Bar::close).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(20), 6, RoundingMode.HALF_UP);
            if (signal.close().compareTo(avg20) <= 0) continue;
            Bar entry = bars.get(i + 1);
            BigDecimal entryPrice = entry.open();
            int quantity = capital.multiply(BigDecimal.valueOf(0.20))
                    .divide(entryPrice.multiply(BigDecimal.valueOf(100)), 0, RoundingMode.DOWN).intValue() * 100;
            if (quantity < 100) continue;
            BigDecimal highest = entryPrice;
            Bar exit = entry; String reason = "MAX_HOLD";
            int maxExit = Math.min(i + 1 + req.maxHoldingDays(), bars.size() - 1);
            for (int j = i + 1; j <= maxExit; j++) {
                Bar b = bars.get(j); highest = highest.max(b.high()); exit = b;
                if (b.low().compareTo(entryPrice.multiply(BigDecimal.ONE.subtract(req.stopLossPct()))) <= 0) { reason = "STOP_LOSS"; break; }
                if (b.high().compareTo(entryPrice.multiply(BigDecimal.ONE.add(req.takeProfitPct()))) >= 0) { reason = "TAKE_PROFIT"; break; }
                if (b.close().compareTo(highest.multiply(BigDecimal.ONE.subtract(req.trailingStopPct()))) <= 0) { reason = "TRAILING_STOP"; break; }
            }
            BigDecimal exitPrice = switch (reason) {
                case "STOP_LOSS" -> entryPrice.multiply(BigDecimal.ONE.subtract(req.stopLossPct()));
                case "TAKE_PROFIT" -> entryPrice.multiply(BigDecimal.ONE.add(req.takeProfitPct()));
                default -> exit.close();
            };
            BigDecimal buyAmount = entryPrice.multiply(BigDecimal.valueOf(quantity));
            BigDecimal sellAmount = exitPrice.multiply(BigDecimal.valueOf(quantity));
            BigDecimal commission = buyAmount.add(sellAmount).multiply(req.commissionRate());
            BigDecimal tax = sellAmount.multiply(req.stampDutyRate());
            BigDecimal pnl = sellAmount.subtract(buyAmount).subtract(commission).subtract(tax);
            capital = capital.add(pnl); peak = peak.max(capital);
            BigDecimal drawdown = peak.subtract(capital).divide(peak, 6, RoundingMode.HALF_UP);
            maxDrawdown = maxDrawdown.max(drawdown);
            trades.add(new Trade(entry.tradeDate(), exit.tradeDate(), entryPrice, exitPrice, quantity, pnl,
                    pnl.divide(buyAmount, 6, RoundingMode.HALF_UP), reason));
        }
        long wins = trades.stream().filter(t -> t.pnl().signum() > 0).count();
        BigDecimal grossWin = trades.stream().filter(t -> t.pnl().signum() > 0).map(Trade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLoss = trades.stream().filter(t -> t.pnl().signum() < 0).map(t -> t.pnl().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades.size()), 6, RoundingMode.HALF_UP);
        BigDecimal plRatio = grossLoss.signum() == 0 ? grossWin : grossWin.divide(grossLoss, 6, RoundingMode.HALF_UP);
        return new Result(req.initialCapital(), capital, capital.subtract(req.initialCapital()).divide(req.initialCapital(), 6, RoundingMode.HALF_UP),
                maxDrawdown, winRate, plRatio, trades.size(), trades);
    }
}
