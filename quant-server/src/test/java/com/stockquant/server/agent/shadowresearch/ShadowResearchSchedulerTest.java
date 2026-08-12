package com.stockquant.server.agent.shadowresearch;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowResearchSchedulerTest {

    @Test
    void eligibleAfterCloseDispatchesOnlyThroughNarrowGateway() {
        ShadowResearchRepository repository = mock(
                ShadowResearchRepository.class);
        AtomicInteger calls = new AtomicInteger();
        ShadowResearchDispatchGateway gateway = (date, asOf) -> {
            calls.incrementAndGet();
            return new ShadowResearchDispatchGateway.DispatchResult(
                    "SQHB_20250910T092000Z_A1B2C3D4E5F6", true);
        };
        Instant now = Instant.parse("2025-09-10T09:20:00Z");
        when(repository.frozenSlot(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.activeRun()).thenReturn(Optional.empty());
        when(repository.researchCalendarOpen(LocalDate.of(2025, 9, 10), now))
                .thenReturn(true);
        var scheduler = new ShadowResearchScheduler(repository, gateway,
                new ShadowResearchScheduleProperties(),
                Clock.fixed(now, ZoneOffset.UTC));

        scheduler.dispatchAfterClose();

        assertEquals(1, calls.get());
        verify(repository).researchCalendarOpen(
                LocalDate.of(2025, 9, 10), now);
    }

    @Test
    void frozenOrUnavailableCalendarNeverDispatches() {
        ShadowResearchRepository repository = mock(
                ShadowResearchRepository.class);
        AtomicInteger calls = new AtomicInteger();
        ShadowResearchDispatchGateway gateway = (date, asOf) -> {
            calls.incrementAndGet();
            return new ShadowResearchDispatchGateway.DispatchResult(
                    "SQHB_20250910T092000Z_A1B2C3D4E5F6", true);
        };
        Instant now = Instant.parse("2025-09-10T09:20:00Z");
        when(repository.frozenSlot(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.activeRun()).thenReturn(Optional.empty());
        when(repository.researchCalendarOpen(any(), any()))
                .thenReturn(false);
        var scheduler = new ShadowResearchScheduler(repository, gateway,
                new ShadowResearchScheduleProperties(),
                Clock.fixed(now, ZoneOffset.UTC));

        scheduler.dispatchAfterClose();
        scheduler.dispatchAfterClose();

        assertEquals(0, calls.get());
    }
}
