package com.stockquant.core.strategy;

import com.stockquant.core.domain.Bar;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MultiFactorShortTermStrategyTest {
    @Test void shouldEvaluateTrend() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            BigDecimal p = BigDecimal.valueOf(10 + i * 0.08);
            bars.add(new Bar("600000", LocalDate.of(2026,1,1).plusDays(i), p, p.add(BigDecimal.valueOf(.2)), p.subtract(BigDecimal.valueOf(.2)), p, 1_000_000 + i * 20_000L, p.multiply(BigDecimal.valueOf(1_000_000)), BigDecimal.valueOf(2)));
        }
        assertNotNull(new MultiFactorShortTermStrategy().evaluate(bars));
    }
}
