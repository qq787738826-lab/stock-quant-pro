package com.stockquant.server.agent.shadowresearch;

import org.junit.jupiter.api.Test;
import com.stockquant.server.production.ShadowSchedulerRuntimeState;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowResearchSchedulerTest {

    @Test
    void continuousBudgetAndScheduleAreFrozenToUserApproval() {
        var values = new ShadowResearchScheduleProperties();
        values.validate();
        assertEquals(true, values.isEnabled());
        assertEquals("0 20 17 * * MON-FRI", values.getCron());
        assertEquals("Asia/Shanghai", values.getZone());
        assertEquals(8, values.getMaximumTushareRequests());
        assertEquals(13, values.getMaximumModelCalls());
        assertEquals(150, values.getMonthlyTushareRequestLimit());
        assertEquals("30.00",
                values.getMonthlyBailianCostLimitCny().toPlainString());
        assertEquals("200.00",
                values.getProjectMonthlyApiCostLimitCny().toPlainString());

        values.setMonthlyTushareRequestLimit(149);
        assertThrows(IllegalStateException.class, values::validate);
    }

    @Test
    void eligibleAfterCloseDispatchesOnlyThroughNarrowGateway() {
        ShadowResearchRepository repository = mock(
                ShadowResearchRepository.class);
        AtomicInteger calls = new AtomicInteger();
        ShadowResearchDispatchGateway gateway = (date, asOf, calendar) -> {
            calls.incrementAndGet();
            assertEquals(ShadowResearchRepository.CalendarState.OPEN,
                    calendar);
            return new ShadowResearchDispatchGateway.DispatchResult(
                    "SQHB_20250910T092000Z_A1B2C3D4E5F6", true);
        };
        Instant now = Instant.parse("2025-09-10T09:20:00Z");
        when(repository.frozenSlot(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.activeRun()).thenReturn(Optional.empty());
        when(repository.researchCalendarState(LocalDate.of(2025, 9, 10), now))
                .thenReturn(ShadowResearchRepository.CalendarState.OPEN);
        when(repository.nextCommonOpenKnown(LocalDate.of(2025, 9, 10), now))
                .thenReturn(true);
        var scheduler = new ShadowResearchScheduler(repository, gateway,
                new ShadowResearchScheduleProperties(),
                Clock.fixed(now, ZoneOffset.UTC),
                new ShadowSchedulerRuntimeState());

        scheduler.dispatchAfterClose();

        assertEquals(1, calls.get());
        verify(repository).researchCalendarState(
                LocalDate.of(2025, 9, 10), now);
    }

    @Test
    void frozenOrUnavailableCalendarNeverDispatches() {
        ShadowResearchRepository repository = mock(
                ShadowResearchRepository.class);
        AtomicInteger calls = new AtomicInteger();
        ShadowResearchDispatchGateway gateway = (date, asOf, calendar) -> {
            calls.incrementAndGet();
            return new ShadowResearchDispatchGateway.DispatchResult(
                    "SQHB_20250910T092000Z_A1B2C3D4E5F6", true);
        };
        Instant now = Instant.parse("2025-09-10T09:20:00Z");
        when(repository.frozenSlot(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.activeRun()).thenReturn(Optional.empty());
        when(repository.researchCalendarState(any(), any()))
                .thenReturn(ShadowResearchRepository.CalendarState.CLOSED);
        var scheduler = new ShadowResearchScheduler(repository, gateway,
                new ShadowResearchScheduleProperties(),
                Clock.fixed(now, ZoneOffset.UTC),
                new ShadowSchedulerRuntimeState());

        scheduler.dispatchAfterClose();
        scheduler.dispatchAfterClose();

        assertEquals(0, calls.get());
    }

    @Test
    void unknownWeekdayUsesBoundedCalendarAdmission() {
        ShadowResearchRepository repository = mock(
                ShadowResearchRepository.class);
        AtomicInteger calls = new AtomicInteger();
        ShadowResearchDispatchGateway gateway = (date, asOf, calendar) -> {
            assertEquals(ShadowResearchRepository.CalendarState.UNKNOWN,
                    calendar);
            calls.incrementAndGet();
            return new ShadowResearchDispatchGateway.DispatchResult(
                    "SQHB_20250910T092000Z_A1B2C3D4E5F6", true);
        };
        Instant now = Instant.parse("2025-09-10T09:20:00Z");
        when(repository.frozenSlot(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.activeRun()).thenReturn(Optional.empty());
        when(repository.researchCalendarState(any(), any()))
                .thenReturn(ShadowResearchRepository.CalendarState.UNKNOWN);

        new ShadowResearchScheduler(repository, gateway,
                new ShadowResearchScheduleProperties(),
                Clock.fixed(now, ZoneOffset.UTC),
                new ShadowSchedulerRuntimeState()).dispatchAfterClose();

        assertEquals(1, calls.get());
    }

    @Test
    void missingFutureCalendarConvertsKnownOpenToBoundedAdmission() {
        ShadowResearchRepository repository = mock(
                ShadowResearchRepository.class);
        AtomicInteger calls = new AtomicInteger();
        ShadowResearchDispatchGateway gateway = (date, asOf, calendar) -> {
            assertEquals(ShadowResearchRepository.CalendarState.UNKNOWN,
                    calendar);
            calls.incrementAndGet();
            return new ShadowResearchDispatchGateway.DispatchResult(
                    "SQHB_20250910T092000Z_A1B2C3D4E5F6", true);
        };
        Instant now = Instant.parse("2025-09-10T09:20:00Z");
        when(repository.frozenSlot(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.activeRun()).thenReturn(Optional.empty());
        when(repository.researchCalendarState(any(), any()))
                .thenReturn(ShadowResearchRepository.CalendarState.OPEN);
        when(repository.nextCommonOpenKnown(any(), any())).thenReturn(false);

        new ShadowResearchScheduler(repository, gateway,
                new ShadowResearchScheduleProperties(),
                Clock.fixed(now, ZoneOffset.UTC),
                new ShadowSchedulerRuntimeState()).dispatchAfterClose();

        assertEquals(1, calls.get());
    }
}
