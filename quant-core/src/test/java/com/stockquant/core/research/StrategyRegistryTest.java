package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrategyRegistryTest {
    private final StrategyRegistry registry = new StrategyRegistry();

    @Test
    void exposesOnlyFourVersionedRepresentativeStrategies() {
        assertEquals(4, registry.catalog().size());
        assertEquals(StrategyRegistry.BUY_AND_HOLD,
                registry.catalog().get(0).strategyCode());
        assertEquals(StrategyResearchModels.STRATEGY_ENGINE_VERSION,
                registry.catalog().get(0).engineVersion());
    }

    @Test
    void parameterizesStrategiesAndRejectsUnknownOrInvalidParameters() {
        Strategy strategy = registry.create(new StrategySpec(
                StrategyRegistry.MOVING_AVERAGE_MOMENTUM, Map.of(
                "shortWindow", "3", "longWindow", "8",
                "targetWeight", "0.2")));
        assertEquals("3", strategy.definition().parameters().get(
                "shortWindow"));
        assertThrows(IllegalArgumentException.class, () -> registry.create(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("dynamicClass", "evil"))));
        assertThrows(IllegalArgumentException.class, () -> registry.create(
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "20", "longWindow", "5"))));
        assertThrows(IllegalArgumentException.class, () -> registry.create(
                StrategySpec.of("UNREGISTERED")));
    }
}
