package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository.BreadthRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentMarketBreadthContextServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-07-16");

    @Test void producesUnifiedDeterministicBreadth() {
        var repository = mock(AgentResearchContextReadRepository.class);
        when(repository.marketBreadth(DATE)).thenReturn(new BreadthRecord(
                DATE, DATE.minusDays(1), 5, 4, 3, 1, 1, 1, 1, 1));
        var value = new AgentMarketBreadthContextService(new ObjectMapper(), repository)
                .create("600000", DATE, Instant.EPOCH);
        assertTrue(value.path("available").asBoolean());
        assertEquals("0.60000000", value.path("coverageRatio").asText());
        assertEquals(5, value.path("comparableSymbolCount").asInt()
                + value.path("missingCurrentBarCount").asInt()
                + value.path("missingPreviousBarCount").asInt());
        assertFalse(value.path("pointInTimeGuaranteed").asBoolean());
        assertTrue(value.path("barFutureDataExcluded").asBoolean());
        assertFalse(value.path("futureDataExcluded").asBoolean());
        for (String forbidden : new String[]{"score", "gateStatus", "decision", "finding", "veto"})
            assertFalse(value.has(forbidden));
    }

    @Test void unavailableReasonPriorityAndNullRatioAreStable() {
        var repository = mock(AgentResearchContextReadRepository.class);
        when(repository.marketBreadth(DATE)).thenReturn(new BreadthRecord(null, null, 0, 0, 0, 0, 0, 0, 0, 0));
        var value = new AgentMarketBreadthContextService(new ObjectMapper(), repository)
                .create("600000", DATE, Instant.EPOCH);
        assertFalse(value.path("available").asBoolean());
        assertEquals("NO_ELIGIBLE_UNIVERSE", value.path("reasonCode").asText());
        assertTrue(value.path("coverageRatio").isNull());
        assertTrue(value.path("effectiveTradeDate").isNull());
    }

    @Test void comparableZeroHasFinalPriority() {
        var repository = mock(AgentResearchContextReadRepository.class);
        when(repository.marketBreadth(DATE)).thenReturn(new BreadthRecord(DATE, DATE.minusDays(1), 3, 2, 0, 0, 0, 0, 1, 2));
        var value = new AgentMarketBreadthContextService(new ObjectMapper(), repository)
                .create("600000", DATE, Instant.EPOCH);
        assertEquals("ZERO_COMPARABLE_SYMBOLS", value.path("reasonCode").asText());
        assertEquals("0E-8", value.path("coverageRatio").asText());
    }
}
