package com.stockquant.server.researchselection;

import com.stockquant.server.agent.marketfacts.TushareManualBoundedSession;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;

import java.time.Instant;

/** Determines the only safe 0/2/52 request envelope before Broker submit. */
public final class ResearchSelectionProviderBudgetPlanner {
    private ResearchSelectionProviderBudgetPlanner() {
    }

    public static int requiredProviderRequests(
            TushareResearchUniverseDatasetLoader loader,
            SelectionRequest request,
            Instant asOf
    ) {
        var anchor = ResearchSelectionAnchorResolver.resolve(loader,
                request.auxiliaryWindow(), asOf);
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

}
