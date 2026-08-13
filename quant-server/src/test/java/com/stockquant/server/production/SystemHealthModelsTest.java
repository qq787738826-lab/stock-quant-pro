package com.stockquant.server.production;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemHealthModelsTest {
    @Test
    void productionHealthCanNeverAdvertiseRealTrading() {
        var value = new SystemHealthModels.SystemHealth("SYSTEM_HEALTH_V1",
                SystemHealthModels.HealthStatus.HEALTHY, "READY",
                "a".repeat(40), Instant.now(), List.of(),
                new SystemHealthModels.BudgetHealth("2026-08", 0, 150,
                        BigDecimal.ZERO, new BigDecimal("30.00"),
                        BigDecimal.ZERO, new BigDecimal("200.00")),
                new SystemHealthModels.SchedulerHealth(true,
                        "Asia/Shanghai", "0 20 17 * * MON-FRI",
                        Instant.now(), "ACTIVE", "AWAITING_SCHEDULE"),
                Map.of(), Map.of(), 0, 0, false);
        assertFalse(value.realTradingEnabled());
        assertThrows(UnsupportedOperationException.class,
                () -> value.latestShadow().put("trade", true));
    }
}
