package com.stockquant.server.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiMonthlyBudgetTest {
    @Test
    void admitsOnlyInsideBothProjectAndShadowLedgers() {
        var admitted = ExternalApiMonthlyBudget.admitShadow(
                YearMonth.of(2026, 8), new BigDecimal("10"),
                new BigDecimal("2"), 6, new BigDecimal("1"), 6);
        assertTrue(admitted.allowed());
        assertEquals(619, admitted.tushareRequestsRemaining());

        var shadowBlocked = ExternalApiMonthlyBudget.admitShadow(
                YearMonth.of(2026, 8), new BigDecimal("30"),
                new BigDecimal("29.50"), 6, BigDecimal.ONE, 6);
        assertFalse(shadowBlocked.allowed());
        assertEquals("SHADOW_MONTHLY_COST_EXHAUSTED",
                shadowBlocked.reason());

        var projectBlocked = ExternalApiMonthlyBudget.admitShadow(
                YearMonth.of(2026, 8), new BigDecimal("199.50"),
                BigDecimal.ONE, 6, BigDecimal.ONE, 6);
        assertFalse(projectBlocked.allowed());
        assertEquals("PROJECT_MONTHLY_COST_EXHAUSTED",
                projectBlocked.reason());
    }

    @Test
    void approvedBackfillMonthsUseTheirLimitsAndOtherMonthsStayAtOneFifty() {
        assertEquals(625, ExternalApiMonthlyBudget.tushareRequestLimit(
                YearMonth.of(2026, 8)));
        assertEquals(450, ExternalApiMonthlyBudget.tushareRequestLimit(
                YearMonth.of(2026, 9)));
        assertEquals(150, ExternalApiMonthlyBudget.tushareRequestLimit(
                YearMonth.of(2026, 10)));

        var result = ExternalApiMonthlyBudget.admitShadow(
                YearMonth.of(2026, 9), BigDecimal.ZERO,
                BigDecimal.ZERO, 445, BigDecimal.ONE, 6);
        assertFalse(result.allowed());
        assertEquals("SHADOW_MONTHLY_TUSHARE_EXHAUSTED", result.reason());
        assertEquals(5, result.tushareRequestsRemaining());
    }
}
