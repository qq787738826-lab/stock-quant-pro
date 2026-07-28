package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.config.AgentShadowProperties;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.TriggerMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentShadowBatchServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-27T09:00:00Z");

    private AgentShadowRepository repository;
    private AgentShadowSelectionService selectionService;
    private AgentShadowBatchService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentShadowRepository.class);
        selectionService = new AgentShadowSelectionService(repository);
        AgentShadowProperties properties = new AgentShadowProperties();
        properties.setEnabled(true);
        service = new AgentShadowBatchService(
                repository,
                selectionService,
                properties,
                new ObjectMapper(),
                mock(ApplicationEventPublisher.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void manualAutoSelectionSkipsWeekendsBeforeReadingCandidates() {
        LocalDate sunday = LocalDate.of(2026, 7, 26);

        service.createManual(
                sunday,
                SelectionMode.AUTO,
                List.of(),
                null,
                "test");

        verifySkipped(
                sunday,
                "SHADOW_AUTO_NON_WORKDAY");
        verify(repository, never()).reliableCalendarOpen(any(), any());
        verify(repository, never()).currentPositionCandidates();
    }

    @Test
    void manualAutoSelectionSkipsReliablyClosedTradingDate() {
        LocalDate monday = LocalDate.of(2026, 7, 27);
        when(repository.reliableCalendarOpen(monday, NOW))
                .thenReturn(Optional.of(false));

        service.createManual(
                monday,
                SelectionMode.AUTO,
                List.of(),
                null,
                "test");

        verifySkipped(
                monday,
                "SHADOW_RELIABLE_CALENDAR_CLOSED");
        verify(repository, never()).currentPositionCandidates();
    }

    private void verifySkipped(LocalDate tradeDate, String reason) {
        verify(repository).insertBatch(
                eq(BatchStatus.FAILED),
                eq(TriggerMode.MANUAL),
                eq(tradeDate),
                eq(AgentShadowContracts.RULE_VERSION),
                eq(SelectionMode.AUTO),
                anyString(),
                eq(AgentShadowContracts.DEFAULT_MAX_SYMBOLS),
                eq(0),
                any(JsonNode.class),
                eq(reason),
                eq(NOW),
                eq(NOW),
                eq("test"));
    }
}
