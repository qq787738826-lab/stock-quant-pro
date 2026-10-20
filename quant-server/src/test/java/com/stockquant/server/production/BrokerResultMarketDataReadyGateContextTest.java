package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;

class BrokerResultMarketDataReadyGateContextTest {
    @Test
    void springContextCreatesReadyGateWithProductionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class,
                    () -> new ObjectMapper());
            context.register(BrokerResultMarketDataReadyGate.class);

            context.refresh();

            var concrete = context.getBean(
                    BrokerResultMarketDataReadyGate.class);
            assertSame(concrete, context.getBean(MarketDataReadyGate.class));
        }
    }
}
