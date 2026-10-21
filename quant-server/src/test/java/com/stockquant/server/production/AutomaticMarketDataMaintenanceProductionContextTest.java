package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.api.MarketDataMaintenanceController;
import com.stockquant.server.agent.shadowresearch.ShadowResearchDispatchGateway;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.agent.shadowresearch.ShadowResearchScheduleProperties;
import com.stockquant.server.agent.shadowresearch.ShadowResearchScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AutomaticMarketDataMaintenanceProductionContextTest {
    @Test
    void springContextCreatesMaintenanceAndShadowProductionBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "stockquant.market-data-maintenance.scheduler.enabled=true",
                    "stockquant.shadow-research.scheduler.enabled=true");
            context.registerBean(ObjectMapper.class,
                    () -> new ObjectMapper());
            context.registerBean(
                    AutomaticMarketDataMaintenanceProperties.class,
                    () -> {
                        var properties =
                                new AutomaticMarketDataMaintenanceProperties();
                        properties.setEnabled(true);
                        return properties;
                    });
            context.registerBean(ShadowResearchScheduleProperties.class,
                    () -> new ShadowResearchScheduleProperties());
            context.registerBean("agentTemporalClock", Clock.class,
                    () -> Clock.fixed(
                            Instant.parse("2026-10-20T03:00:00Z"),
                            ZoneOffset.UTC));
            context.registerBean(
                    AutomaticMarketDataMaintenanceRuntimeState.class);
            context.registerBean(ShadowSchedulerRuntimeState.class);
            context.registerBean(ShadowResearchRepository.class,
                    () -> mock(ShadowResearchRepository.class));
            context.registerBean(ShadowResearchDispatchGateway.class,
                    () -> mock(ShadowResearchDispatchGateway.class));
            context.register(
                    BrokerResultMarketDataReadyGate.class,
                    BrokerAutomaticMarketDataMaintenanceStatusQuery.class,
                    PowerShellAutomaticMarketDataMaintenanceDispatchGateway.class,
                    AutomaticDailyMarketDataMaintenanceScheduler.class,
                    ManualMarketDataMaintenanceService.class,
                    MarketDataMaintenanceController.class,
                    ShadowResearchScheduler.class);

            context.refresh();

            var gate = context.getBean(
                    BrokerResultMarketDataReadyGate.class);
            var gateway = context.getBean(
                    PowerShellAutomaticMarketDataMaintenanceDispatchGateway.class);
            assertSame(gate, context.getBean(MarketDataReadyGate.class));
            assertSame(gateway, context.getBean(
                    AutomaticMarketDataMaintenanceDispatchGateway.class));
            assertNotNull(context.getBean(
                    AutomaticDailyMarketDataMaintenanceScheduler.class));
            assertNotNull(context.getBean(
                    MarketDataMaintenanceController.class));
            assertSame(context.getBean(
                            BrokerAutomaticMarketDataMaintenanceStatusQuery.class),
                    context.getBean(
                            AutomaticMarketDataMaintenanceStatusQuery.class));
            assertNotNull(context.getBean(ShadowResearchScheduler.class));
            assertTrue(context.getBean(
                    AutomaticMarketDataMaintenanceProperties.class)
                    .isEnabled());
        }
    }
}
