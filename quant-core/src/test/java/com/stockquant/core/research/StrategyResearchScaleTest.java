package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyResearchScaleTest {
    @Test
    void comparesFourStrategiesAcrossTwentySecuritiesAndOneThousandSessions() {
        var dataset = StrategyResearchTestFixtures.dataset(20, 1_000);
        var api = new DefaultStrategyResearchApi();
        Instant started = Instant.now();
        var result = api.compare(dataset, List.of(
                        StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                        new StrategySpec(
                                StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                                Map.of("shortWindow", "10",
                                        "longWindow", "50",
                                        "targetWeight", "0.08")),
                        new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                                Map.of("lookback", "20",
                                        "entryDeviation", "0.02",
                                        "exitDeviation", "0.00",
                                        "targetWeight", "0.08")),
                        new StrategySpec(
                                StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                                Map.of("lookback", "60", "topN", "5",
                                        "rebalanceEvery", "20",
                                        "targetGrossExposure", "0.80"))),
                BacktestConfig.standard(), dataset.firstSessionDate(),
                dataset.lastSessionDate(), dataset.securities().get(0));
        Duration elapsed = Duration.between(started, Instant.now());

        assertEquals(4, result.strategies().size());
        assertTrue(result.strategies().stream().allMatch(strategy ->
                strategy.fingerprint().length() == 64));
        assertTrue(elapsed.compareTo(Duration.ofSeconds(30)) < 0,
                () -> "M2 scale smoke exceeded 30 seconds: " + elapsed);
    }
}
