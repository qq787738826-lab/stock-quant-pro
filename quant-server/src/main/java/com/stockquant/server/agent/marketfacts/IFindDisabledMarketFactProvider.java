package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import org.springframework.stereotype.Component;

/**
 * Compile-time adapter seam only. It deliberately has no HTTP/SDK dependency
 * and always fails before any external action.
 */
@Component
public final class IFindDisabledMarketFactProvider implements MarketFactProvider {

    private final IFindTrialProperties properties;

    public IFindDisabledMarketFactProvider(IFindTrialProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProviderCapability capability() {
        throw blocked();
    }

    @Override
    public MarketFactResponse fetch(MarketFactRequest request) {
        throw blocked();
    }

    public boolean networkClientCreated() {
        return false;
    }

    private IllegalStateException blocked() {
        String state = properties.getActivationGate();
        return new IllegalStateException(
                PitMarketFactsContracts.IFIND_GATE_NOT_PASSED
                        + ": activationGate=" + (state == null ? "BLOCKED" : state)
                        + "; enabled=" + properties.isEnabled());
    }
}
