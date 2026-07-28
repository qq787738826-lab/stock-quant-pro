package com.stockquant.server.agent.marketfacts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit runtime gate for synthetic V2 facts. Production defaults must never
 * make TEST/DEMO market facts available implicitly.
 */
@ConfigurationProperties(prefix = "stockquant.market-facts.v2")
public class PitMarketFactsV2Properties {

    private boolean testDemoEnabled;

    public boolean isTestDemoEnabled() {
        return testDemoEnabled;
    }

    public void setTestDemoEnabled(boolean testDemoEnabled) {
        this.testDemoEnabled = testDemoEnabled;
    }
}
