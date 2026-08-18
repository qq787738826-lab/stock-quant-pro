package com.stockquant.server.agent.evaluation;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/** Shared project ceiling plus deliberately smaller M4/M5 sub-ledgers. */
public final class ExternalApiMonthlyBudget {
    public static final BigDecimal PROJECT_MONTHLY_COST_CNY =
            new BigDecimal("200.00");
    public static final BigDecimal SHADOW_MONTHLY_COST_CNY =
            new BigDecimal("30.00");
    /** Normal monthly Tushare ceiling outside an explicitly approved month. */
    public static final int SHADOW_MONTHLY_TUSHARE_REQUESTS = 150;
    /** One-time V1.0.9 mainboard backfill authorization for 2026-08 only. */
    public static final int AUGUST_2026_TUSHARE_REQUESTS = 250;
    private static final YearMonth AUGUST_2026 = YearMonth.of(2026, 8);
    public static final BigDecimal M5_DEVELOPMENT_COST_CNY =
            new BigDecimal("5.00");
    public static final int M5_DEVELOPMENT_TUSHARE_REQUESTS = 0;

    private ExternalApiMonthlyBudget() {
    }

    public static int tushareRequestLimit(YearMonth month) {
        Objects.requireNonNull(month, "month");
        return AUGUST_2026.equals(month) ? AUGUST_2026_TUSHARE_REQUESTS
                : SHADOW_MONTHLY_TUSHARE_REQUESTS;
    }

    public static Admission admitShadow(
            YearMonth month,
            BigDecimal projectCostBefore,
            BigDecimal shadowCostBefore,
            int tushareBefore,
            BigDecimal requestedCost,
            int requestedTushare
    ) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(projectCostBefore, "projectCostBefore");
        Objects.requireNonNull(shadowCostBefore, "shadowCostBefore");
        Objects.requireNonNull(requestedCost, "requestedCost");
        boolean valid = projectCostBefore.signum() >= 0
                && shadowCostBefore.signum() >= 0 && tushareBefore >= 0
                && requestedCost.signum() > 0 && requestedTushare > 0;
        int tushareLimit = tushareRequestLimit(month);
        boolean allowed = valid
                && projectCostBefore.add(requestedCost).compareTo(
                PROJECT_MONTHLY_COST_CNY) <= 0
                && shadowCostBefore.add(requestedCost).compareTo(
                SHADOW_MONTHLY_COST_CNY) <= 0
                && tushareBefore + requestedTushare
                <= tushareLimit;
        String reason = !valid ? "MONTHLY_LEDGER_INVALID"
                : allowed ? "ADMITTED"
                : projectCostBefore.add(requestedCost).compareTo(
                        PROJECT_MONTHLY_COST_CNY) > 0
                ? "PROJECT_MONTHLY_COST_EXHAUSTED"
                : shadowCostBefore.add(requestedCost).compareTo(
                        SHADOW_MONTHLY_COST_CNY) > 0
                ? "SHADOW_MONTHLY_COST_EXHAUSTED"
                : "SHADOW_MONTHLY_TUSHARE_EXHAUSTED";
        return new Admission(month, allowed, reason, projectCostBefore,
                shadowCostBefore, tushareBefore,
                PROJECT_MONTHLY_COST_CNY.subtract(projectCostBefore)
                        .max(BigDecimal.ZERO),
                SHADOW_MONTHLY_COST_CNY.subtract(shadowCostBefore)
                        .max(BigDecimal.ZERO),
                Math.max(0, tushareLimit - tushareBefore));
    }

    public record Admission(
            YearMonth calendarMonth,
            boolean allowed,
            String reason,
            BigDecimal projectCostBefore,
            BigDecimal shadowCostBefore,
            int tushareRequestsBefore,
            BigDecimal projectCostRemaining,
            BigDecimal shadowCostRemaining,
            int tushareRequestsRemaining
    ) {
    }
}
