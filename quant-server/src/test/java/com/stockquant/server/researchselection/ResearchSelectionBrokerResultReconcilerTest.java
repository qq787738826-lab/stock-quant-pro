package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchSelectionBrokerResultReconcilerTest {
    private static final String ID =
            "SQHB_20260813T100000Z_A1B2C3D4E5F6";

    @TempDir
    Path temporary;

    @Test
    void productionConstructorIsExplicitlySelectedForSpring() {
        var constructors = Arrays.stream(
                        ResearchSelectionBrokerResultReconciler.class
                                .getDeclaredConstructors())
                .filter(value -> value.isAnnotationPresent(Autowired.class))
                .toList();

        assertEquals(1, constructors.size());
        assertEquals(3, constructors.get(0).getParameterCount());
    }

    @Test
    void acceptsOnlySanitizedZeroCallPreRunnerFailure() throws Exception {
        Path file = temporary.resolve(ID + ".result.json");
        Files.writeString(file, """
                {"requestId":"%s","operation":"RUN_RESEARCH_SELECTION",
                 "status":"REJECTED","reason":"STOCK_QUANT_HOST_BROKER_BUILD_INVALID",
                 "providerCallCount":0,"retryCount":0,"noRetry":true}
                """.formatted(ID));

        var failure = ResearchSelectionBrokerResultReconciler.failure(
                new ObjectMapper(), file, ID).orElseThrow();

        assertEquals("STOCK_QUANT_HOST_BROKER_BUILD_INVALID",
                failure.reason());
    }

    @Test
    void rejectsForeignOrProviderConsumingResult() throws Exception {
        Path file = temporary.resolve(ID + ".result.json");
        Files.writeString(file, """
                {"requestId":"%s","operation":"RUN_RESEARCH_SELECTION",
                 "status":"FAILED","reason":"RESEARCH_SELECTION_FAILED",
                 "providerCallCount":1,"retryCount":0,"noRetry":true}
                """.formatted(ID));

        assertTrue(ResearchSelectionBrokerResultReconciler.failure(
                new ObjectMapper(), file, ID).isEmpty());
    }

    @Test
    void staleRunsResolveToSafeTerminalReasonsWithoutRetryingBrokerWork() {
        Instant now = Instant.parse("2026-11-23T01:00:00Z");
        Instant stale = now.minus(
                ResearchSelectionBrokerResultReconciler.STALE_RUN_TIMEOUT);
        var mapper = new ObjectMapper();

        assertEquals("RESEARCH_SELECTION_BROKER_BINDING_TIMEOUT",
                ResearchSelectionBrokerResultReconciler
                        .reconciliationFailure(mapper, temporary,
                                active(1, null, stale), now)
                        .orElseThrow());
        assertEquals("RESEARCH_SELECTION_BROKER_RESULT_TIMEOUT",
                ResearchSelectionBrokerResultReconciler
                        .reconciliationFailure(mapper, temporary,
                                active(2, ID, stale), now)
                        .orElseThrow());
        assertTrue(ResearchSelectionBrokerResultReconciler
                .reconciliationFailure(mapper, temporary,
                        active(3, ID, stale.plusNanos(1)), now)
                .isEmpty());
    }

    @Test
    void reconcilerWritesOneFailedTerminalForAnAbandonedRun() {
        Instant now = Instant.parse("2026-11-23T01:00:00Z");
        var run = active(6, ID, now.minus(
                ResearchSelectionBrokerResultReconciler.STALE_RUN_TIMEOUT));
        ResearchSelectionRepository repository = mock(
                ResearchSelectionRepository.class);
        when(repository.queuedRunsForReconciliation(20))
                .thenReturn(List.of(run));
        var reconciler = new ResearchSelectionBrokerResultReconciler(
                repository, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC), temporary);

        reconciler.reconcile();

        verify(repository).fail(6, ResearchSelectionModels.Status.QUEUED,
                "BROKER", "RESEARCH_SELECTION_BROKER_RESULT_TIMEOUT",
                now);
    }

    @Test
    void sanitizedBrokerFailureWinsBeforeStaleTimeout() throws Exception {
        Path file = temporary.resolve(ID + ".result.json");
        Files.writeString(file, """
                {"requestId":"%s","operation":"RUN_RESEARCH_SELECTION",
                 "status":"FAILED","reason":"STOCK_QUANT_HOST_BROKER_FAILED",
                 "providerCallCount":0,"retryCount":0,"noRetry":true}
                """.formatted(ID));
        Instant now = Instant.parse("2026-11-23T01:00:00Z");

        assertEquals("STOCK_QUANT_HOST_BROKER_FAILED",
                ResearchSelectionBrokerResultReconciler
                        .reconciliationFailure(new ObjectMapper(), temporary,
                                active(5, ID, now), now)
                        .orElseThrow());
    }

    private static ResearchSelectionRepository.QueuedRun active(
            long id,
            String requestId,
            Instant updatedAt
    ) {
        return new ResearchSelectionRepository.QueuedRun(id, requestId,
                updatedAt);
    }
}
