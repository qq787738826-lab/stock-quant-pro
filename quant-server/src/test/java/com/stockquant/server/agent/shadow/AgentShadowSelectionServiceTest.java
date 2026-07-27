package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentShadowSelectionServiceTest {

    private final AgentShadowRepository repository =
            mock(AgentShadowRepository.class);
    private final AgentShadowSelectionService service =
            new AgentShadowSelectionService(repository);

    @Test
    void explicitSelectionDeduplicatesSortsAndHashesFrozenFacts() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 27);
        var first = service.select(
                SelectionMode.EXPLICIT,
                List.of("600000", "000001", "600000"),
                10,
                tradeDate);
        var repeated = service.select(
                SelectionMode.EXPLICIT,
                List.of("000001", "600000"),
                10,
                tradeDate);

        assertEquals(List.of("000001", "600000"),
                first.entries().stream()
                        .map(value -> value.symbol()).toList());
        assertEquals(List.of(1, 2),
                first.entries().stream()
                        .map(value -> value.selectionOrder()).toList());
        assertEquals(first.selectionHash(), repeated.selectionHash());
        assertEquals(64, first.selectionHash().length());
        assertNotEquals(
                first.selectionHash(),
                service.select(
                        SelectionMode.EXPLICIT,
                        List.of("000001", "600000"),
                        1,
                        tradeDate).selectionHash());
    }

    @Test
    void automaticSelectionKeepsPositionPriorityAndStableScanOrder() {
        when(repository.currentPositionCandidates()).thenReturn(List.of(
                candidate("600002",
                        SelectionSource.CURRENT_POSITION, "p2"),
                candidate("600001",
                        SelectionSource.CURRENT_POSITION, "p1")));
        when(repository.latestCompletedScanTaskId())
                .thenReturn(Optional.of(77L));
        when(repository.eligibleScanCandidates(77L)).thenReturn(List.of(
                candidate("600001",
                        SelectionSource.LATEST_SCAN_CANDIDATE, "s1"),
                candidate("000001",
                        SelectionSource.LATEST_SCAN_CANDIDATE, "s2"),
                candidate("000002",
                        SelectionSource.LATEST_SCAN_CANDIDATE, "s3")));

        var result = service.select(
                SelectionMode.AUTO,
                List.of(),
                4,
                LocalDate.of(2026, 7, 27));

        assertEquals(
                List.of("600002", "600001", "000001", "000002"),
                result.entries().stream()
                        .map(value -> value.symbol()).toList());
        assertEquals(
                List.of(
                        SelectionSource.CURRENT_POSITION,
                        SelectionSource.CURRENT_POSITION,
                        SelectionSource.LATEST_SCAN_CANDIDATE,
                        SelectionSource.LATEST_SCAN_CANDIDATE),
                result.entries().stream()
                        .map(value -> value.selectionSource()).toList());
    }

    @Test
    void rejectsInvalidExplicitAndLimits() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 27);
        assertThrows(IllegalArgumentException.class, () ->
                service.select(
                        SelectionMode.EXPLICIT,
                        List.of(),
                        10,
                        tradeDate));
        assertThrows(IllegalArgumentException.class, () ->
                service.select(
                        SelectionMode.EXPLICIT,
                        List.of("ABC"),
                        10,
                        tradeDate));
        assertThrows(IllegalArgumentException.class, () ->
                service.select(
                        SelectionMode.AUTO,
                        List.of(),
                        21,
                        tradeDate));
    }

    private static AgentShadowRepository.SelectionCandidate candidate(
            String symbol,
            SelectionSource source,
            String sourceRef
    ) {
        return new AgentShadowRepository.SelectionCandidate(
                symbol, source, sourceRef);
    }
}
