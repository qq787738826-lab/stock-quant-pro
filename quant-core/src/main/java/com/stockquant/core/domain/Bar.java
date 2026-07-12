package com.stockquant.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Bar(
        String symbol,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal amount,
        BigDecimal turnoverRate
) {}
