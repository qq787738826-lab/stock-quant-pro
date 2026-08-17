package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;

import java.util.List;
import java.util.Map;

/** Fixed V1 strategy set shared by current and historical research. */
public final class ResearchSelectionStrategies {
    private ResearchSelectionStrategies() {
    }

    public static List<StrategySpec> fixed() {
        return List.of(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("symbol", "ALL", "targetWeight", "0.80")),
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "5", "longWindow", "20",
                                "targetWeight", "0.20")),
                new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                        Map.of("lookback", "10", "entryDeviation", "0.02",
                                "exitDeviation", "0.00",
                                "targetWeight", "0.20")),
                new StrategySpec(StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                        Map.of("lookback", "20", "topN", "3",
                                "rebalanceEvery", "5",
                                "targetGrossExposure", "0.60")));
    }

    /** Same four strategy IDs with safe single-security research weights. */
    public static List<StrategySpec> singleSecurityHistorical() {
        return List.of(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("symbol", "ALL", "targetWeight", "0.40")),
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "5", "longWindow", "20",
                                "targetWeight", "0.30")),
                new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                        Map.of("lookback", "10", "entryDeviation", "0.02",
                                "exitDeviation", "0.00",
                                "targetWeight", "0.30")),
                new StrategySpec(StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                        Map.of("lookback", "20", "topN", "1",
                                "rebalanceEvery", "5",
                                "targetGrossExposure", "0.40")));
    }
}
