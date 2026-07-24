package com.stockquant.core.backtest;

import com.stockquant.core.domain.Bar;
import com.stockquant.core.domain.BacktestModels.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BacktestEngine {

    public static final String STRATEGY_CODE = "SMA20_NEXT_OPEN_RISK_EXIT_V1";
    public static final String ENGINE_VERSION = "BACKTEST_ENGINE_V1";
    public static final int MINIMUM_BARS = 30;

    public Result run(List<Bar> bars, Request req) {
        validate(bars, req);
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

    private static void validate(List<Bar> bars, Request request) {
        if (bars == null || bars.size() < MINIMUM_BARS) {
            throw new IllegalArgumentException("回测至少需要30条K线");
        }
        if (request == null
                || request.initialCapital() == null
                || request.stopLossPct() == null
                || request.takeProfitPct() == null
                || request.trailingStopPct() == null
                || request.commissionRate() == null
                || request.stampDutyRate() == null) {
            throw new IllegalArgumentException("回测参数不能为空");
        }
        if (request.initialCapital().signum() <= 0 || request.maxHoldingDays() <= 0) {
            throw new IllegalArgumentException("初始资金和最大持有期必须为正");
        }
        requireRatio(request.stopLossPct(), "stopLossPct");
        requireRatio(request.takeProfitPct(), "takeProfitPct");
        requireRatio(request.trailingStopPct(), "trailingStopPct");
        requireRatio(request.commissionRate(), "commissionRate");
        requireRatio(request.stampDutyRate(), "stampDutyRate");

        String symbol = null;
        LocalDate previousDate = null;
        for (Bar bar : bars) {
            if (bar == null || bar.symbol() == null || bar.symbol().isBlank()
                    || bar.tradeDate() == null || bar.open() == null || bar.high() == null
                    || bar.low() == null || bar.close() == null) {
                throw new IllegalArgumentException("回测K线必要字段不能为空");
            }
            if (symbol == null) {
                symbol = bar.symbol();
            } else if (!Objects.equals(symbol, bar.symbol())) {
                throw new IllegalArgumentException("回测K线证券代码必须一致");
            }
            if (previousDate != null && !bar.tradeDate().isAfter(previousDate)) {
                throw new IllegalArgumentException("回测K线日期必须严格递增");
            }
            previousDate = bar.tradeDate();
            if (bar.open().signum() <= 0 || bar.high().signum() <= 0
                    || bar.low().signum() <= 0 || bar.close().signum() <= 0
                    || bar.volume() < 0
                    || bar.amount() != null && bar.amount().signum() < 0
                    || bar.turnoverRate() != null && bar.turnoverRate().signum() < 0
                    || bar.high().compareTo(bar.open()) < 0
                    || bar.high().compareTo(bar.close()) < 0
                    || bar.high().compareTo(bar.low()) < 0
                    || bar.low().compareTo(bar.open()) > 0
                    || bar.low().compareTo(bar.close()) > 0) {
                throw new IllegalArgumentException("回测K线数值或OHLC关系非法");
            }
        }
    }

    private static void requireRatio(BigDecimal value, String name) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + "必须在[0,1)范围内");
        }
    }
}
