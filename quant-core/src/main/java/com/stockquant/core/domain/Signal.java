package com.stockquant.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record Signal(
        String symbol,
        LocalDate signalDate,
        String action,
        BigDecimal score,
        BigDecimal referencePrice,
        List<String> reasons,
        List<String> risks
) {}
