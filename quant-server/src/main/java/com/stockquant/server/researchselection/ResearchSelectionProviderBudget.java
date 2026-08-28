package com.stockquant.server.researchselection;

import com.stockquant.server.agent.marketfacts.TushareManualBoundedSession;
import com.stockquant.server.researchselection.ResearchUniverseMainboard
        .BackfillPlan;

import java.util.Objects;

/** Exact endpoint-level provider budget bound into one selection request. */
public record ResearchSelectionProviderBudget(
        int stockBasicRequests,
        int dailyRequests,
        int adjustmentFactorRequests,
        int tradeCalendarRequests,
        int networkRecoveryRequests,
        int maximumProviderRequests
) {
    public static final int MAXIMUM_REQUESTS = 503;

    public ResearchSelectionProviderBudget {
        long baseRequests = (long) stockBasicRequests + dailyRequests
                + adjustmentFactorRequests + tradeCalendarRequests;
        long expectedTotal = baseRequests + networkRecoveryRequests;
        if ((stockBasicRequests != 0 && stockBasicRequests != 1)
                || dailyRequests < 0
                || adjustmentFactorRequests < 0
                || dailyRequests != adjustmentFactorRequests
                || tradeCalendarRequests != 0
                || (networkRecoveryRequests != 0
                && networkRecoveryRequests != TushareManualBoundedSession
                .MAINBOARD_MAX_NETWORK_RECOVERIES)
                || (baseRequests == 0 && networkRecoveryRequests != 0)
                || (baseRequests > 0 && networkRecoveryRequests
                != TushareManualBoundedSession
                .MAINBOARD_MAX_NETWORK_RECOVERIES)
                || maximumProviderRequests < 0
                || maximumProviderRequests > MAXIMUM_REQUESTS
                || expectedTotal != maximumProviderRequests) {
            throw new IllegalArgumentException(
                    "RESEARCH_SELECTION_FIXED_SCOPE_INVALID");
        }
    }

    public static ResearchSelectionProviderBudget from(BackfillPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new ResearchSelectionProviderBudget(
                plan.stockBasicRequests(), plan.dailyRequests(),
                plan.adjustmentFactorRequests(),
                plan.tradeCalendarRequests(),
                plan.networkRecoveryRequests(), plan.totalRequests());
    }
}
