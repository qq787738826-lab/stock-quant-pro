package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.config.AgentShadowProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentShadowJobTest {

    @Test
    void safeWindowAndWeekdayAreDeterministic() {
        AgentShadowProperties properties = new AgentShadowProperties();
        AgentShadowJob job = new AgentShadowJob(
                mock(AgentShadowBatchService.class),
                mock(AgentShadowRepository.class),
                properties,
                Clock.fixed(
                        Instant.parse("2026-07-27T09:00:00Z"),
                        ZoneOffset.UTC));

        assertFalse(job.withinSafeWindow(LocalTime.of(16, 39, 59)));
        assertTrue(job.withinSafeWindow(LocalTime.of(16, 40)));
        assertTrue(job.withinSafeWindow(LocalTime.of(18, 30)));
        assertFalse(job.withinSafeWindow(LocalTime.of(18, 30, 1)));
        assertTrue(AgentShadowJob.weekday(
                LocalDate.of(2026, 7, 27)));
        assertFalse(AgentShadowJob.weekday(
                LocalDate.of(2026, 7, 26)));
    }

    @Test
    void frozenPropertiesRejectUnsafeContractChanges() {
        AgentShadowProperties properties = new AgentShadowProperties();
        properties.validateFrozenContract();
        properties.setRuleVersion("another-rule");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                properties::validateFrozenContract);
    }
}
