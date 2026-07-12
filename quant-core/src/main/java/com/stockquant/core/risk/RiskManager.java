package com.stockquant.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class RiskManager {
    public record Context(BigDecimal totalCapital, BigDecimal availableCash, int currentPositions,
                          BigDecimal accountDrawdown, BigDecimal dailyLossPct) {}
    public record Decision(boolean allowed, int quantity, BigDecimal suggestedAmount, List<String> messages) {}

    public Decision validateBuy(Context ctx, BigDecimal price, BigDecimal requestedWeight,
                                int maxPositions, BigDecimal maxPositionWeight) {
        List<String> messages = new ArrayList<>();
        if (ctx.currentPositions() >= maxPositions) messages.add("已达到最大持仓数量");
        if (ctx.accountDrawdown().compareTo(BigDecimal.valueOf(0.12)) >= 0) messages.add("账户回撤达到12%，暂停开仓");
        if (ctx.dailyLossPct().compareTo(BigDecimal.valueOf(0.03)) >= 0) messages.add("当日亏损达到3%，暂停开仓");
        BigDecimal weight = requestedWeight.min(maxPositionWeight).max(BigDecimal.ZERO);
        BigDecimal amount = ctx.totalCapital().multiply(weight).min(ctx.availableCash());
        int quantity = amount.divide(price.multiply(BigDecimal.valueOf(100)), 0, RoundingMode.DOWN).intValue() * 100;
        if (quantity < 100) messages.add("可用资金不足100股");
        return new Decision(messages.isEmpty(), quantity, price.multiply(BigDecimal.valueOf(quantity)), messages);
    }
}
