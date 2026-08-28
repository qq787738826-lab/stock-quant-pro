package com.stockquant.server.researchselection;

import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;

public interface ResearchSelectionDispatchGateway {
    String dispatch(
            ResearchSelectionModels.RunSummary run,
            SelectionRequest request,
            ResearchSelectionProviderBudget providerBudget
    );
}
