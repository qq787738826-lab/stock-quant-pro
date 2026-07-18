package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository;
import com.stockquant.server.agent.repository.AgentResearchContextReadRepository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentScanResultContextServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-07-16");
    private static final String SYMBOL = "600000";

    @Test void mapsWhitelistedResultAndControlledFailureFacts() {
        var repository = mock(AgentResearchContextReadRepository.class);
        var task = task();
        when(repository.latestOfficialScan(DATE)).thenReturn(Optional.of(task));
        when(repository.scanResult(7, SYMBOL)).thenReturn(Optional.of(new ScanResultRecord(
                8, 7, SYMBOL, DATE, 2, false, "[\" B \",1,\"A\",\"A\",\"\"]",
                new BigDecimal("58.50"), new BigDecimal("10.25"), "LOCAL", task.createdAt(),
                null, new BigDecimal("1.2"), null, null, null, null, false)));
        when(repository.scanFailure(7, SYMBOL)).thenReturn(Optional.of(new ScanFailureRecord(
                9, 7, SYMBOL, 1, false, task.createdAt(), task.finishedAt())));
        var value = service(repository).create(SYMBOL, DATE, Instant.EPOCH);
        assertTrue(value.path("available").asBoolean());
        assertFalse(value.path("symbolSelected").asBoolean());
        assertEquals("58.50", value.path("sourceScanScore").asText());
        assertEquals("A", value.path("filterReasons").get(0).asText());
        assertEquals("B", value.path("filterReasons").get(1).asText());
        assertTrue(value.path("limitations").toString().contains("FILTER_REASONS_CONTAINS_NON_STRING_VALUE"));
        assertTrue(value.path("limitations").toString().contains("RESULT_AND_FAILURE_RECORDS_COEXIST"));
        assertTrue(value.path("failureFact").has("retryCount"));
        for (String forbidden : new String[]{"score", "buy_low", "buyLow", "summary", "bullish", "bearish", "suggestedWeight", "signalLevel", "riskLevel"})
            assertFalse(value.has(forbidden));
    }

    @Test void noResultAndNoFailureMeansParticipationUnknown() {
        var repository = mock(AgentResearchContextReadRepository.class);
        when(repository.latestOfficialScan(DATE)).thenReturn(Optional.of(task()));
        when(repository.scanResult(7, SYMBOL)).thenReturn(Optional.empty());
        when(repository.scanFailure(7, SYMBOL)).thenReturn(Optional.empty());
        var value = service(repository).create(SYMBOL, DATE, Instant.EPOCH);
        assertTrue(value.path("available").asBoolean());
        assertFalse(value.path("symbolParticipationKnown").asBoolean());
        assertTrue(value.path("symbolSelected").isNull());
        assertFalse(value.has("symbolNotSelected"));
    }

    @Test void dateMismatchIsUnavailableButPreservesBothDates() {
        var repository = mock(AgentResearchContextReadRepository.class);
        when(repository.latestOfficialScan(DATE)).thenReturn(Optional.of(task()));
        when(repository.scanResult(7, SYMBOL)).thenReturn(Optional.of(new ScanResultRecord(
                8, 7, SYMBOL, DATE.minusDays(1), 1, true, "[]", BigDecimal.ONE,
                BigDecimal.TEN, null, null, null, null, null, null, null, null, true)));
        when(repository.scanFailure(7, SYMBOL)).thenReturn(Optional.empty());
        var value = service(repository).create(SYMBOL, DATE, Instant.EPOCH);
        assertFalse(value.path("available").asBoolean());
        assertEquals("SCAN_TASK_RESULT_DATE_MISMATCH", value.path("reasonCode").asText());
        assertEquals(DATE.toString(), value.path("sourceTaskTradeDate").asText());
        assertEquals(DATE.minusDays(1).toString(), value.path("sourceResultTradeDate").asText());
    }

    @Test void noEligibleTaskIsExplicitAndDoesNotQuerySymbolRows() {
        var repository = mock(AgentResearchContextReadRepository.class);
        when(repository.latestOfficialScan(DATE)).thenReturn(Optional.empty());
        var value = service(repository).create(SYMBOL, DATE, Instant.EPOCH);
        assertEquals("NO_ELIGIBLE_OFFICIAL_SCAN_TASK", value.path("reasonCode").asText());
        verify(repository, never()).scanResult(anyLong(), anyString());
        assertTrue(value.path("readSelectionFutureExcluded").asBoolean());
        assertFalse(value.path("producerInputCutoffGuaranteed").asBoolean());
        assertFalse(value.path("futureDataExcluded").asBoolean());
    }

    private static ScanTaskRecord task() {
        return new ScanTaskRecord(7, "COMPLETED", "FULL", true, null, DATE,
                LocalDateTime.parse("2026-07-16T09:00:00"), LocalDateTime.parse("2026-07-16T09:20:00"));
    }
    private static AgentScanResultContextService service(AgentResearchContextReadRepository repository) {
        return new AgentScanResultContextService(new ObjectMapper(), repository);
    }
}
