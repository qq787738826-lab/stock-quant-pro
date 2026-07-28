package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;

/** An upstream-only adapter. Implementations cannot persist local facts. */
public interface MarketFactProvider {

    ProviderCapability capability();

    MarketFactResponse fetch(MarketFactRequest request);
}
