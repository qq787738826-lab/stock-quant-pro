package com.stockquant.server.researchselection;

import com.stockquant.server.agent.marketfacts.TushareManualBoundedSession;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Determines the only safe 0/2/52 request envelope before Broker submit. */
public final class ResearchSelectionProviderBudgetPlanner {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private ResearchSelectionProviderBudgetPlanner() {
    }

    public static int requiredProviderRequests(
            TushareResearchUniverseDatasetLoader loader,
            SelectionRequest request,
            Instant asOf
    ) {
        LocalDate anchor = resolveAnchor(loader, asOf);
        try {
            loader.load(ResearchUniverseV1.securities(),
                    request.auxiliaryWindow(), anchor, asOf);
            return 0;
        } catch (TushareResearchUniverseDatasetLoader
                 .IncompleteUniverseException incomplete) {
            return incomplete.incrementalAnchorOnly() ? 2
                    : TushareManualBoundedSession
                    .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS;
        } catch (IllegalStateException missing) {
            if ("RESEARCH_UNIVERSE_CALENDAR_WINDOW_INCOMPLETE".equals(
                    missing.getMessage())) {
                return TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS;
            }
            throw missing;
        }
    }

    private static LocalDate resolveAnchor(
            TushareResearchUniverseDatasetLoader loader,
            Instant asOf
    ) {
        LocalDate local = asOf.atZone(SHANGHAI).toLocalDate();
        try {
            return loader.latestCommonOpenDate(ResearchUniverseV1.securities(),
                    local, asOf);
        } catch (IllegalStateException missing) {
            if (!"RESEARCH_UNIVERSE_COMMON_OPEN_SESSION_MISSING".equals(
                    missing.getMessage())) {
                throw missing;
            }
            if (asOf.isBefore(com.stockquant.core.research
                    .StrategyResearchModels.closeInstant(local))) {
                local = local.minusDays(1);
            }
            while (local.getDayOfWeek() == DayOfWeek.SATURDAY
                    || local.getDayOfWeek() == DayOfWeek.SUNDAY) {
                local = local.minusDays(1);
            }
            return local;
        }
    }
}
