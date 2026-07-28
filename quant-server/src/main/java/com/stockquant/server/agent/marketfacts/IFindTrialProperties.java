package com.stockquant.server.agent.marketfacts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** No credentials are modeled before the user-controlled activation gate passes. */
@ConfigurationProperties(prefix = "stockquant.market-facts.ifind")
public class IFindTrialProperties {

    private boolean enabled;
    private String activationGate = "BLOCKED";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getActivationGate() {
        return activationGate;
    }

    public void setActivationGate(String activationGate) {
        this.activationGate = activationGate;
    }
}
