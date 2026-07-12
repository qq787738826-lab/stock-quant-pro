package com.stockquant.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradePlan(
        String symbol,
        LocalDate planDate,
        BigDecimal score,
        BigDecimal buyLow,
        BigDecimal buyHigh,
        BigDecimal stopLoss,
        BigDecimal target1,
        BigDecimal target2,
        BigDecimal suggestedWeight,
        int validTradingDays,
        String rationale
) {}
