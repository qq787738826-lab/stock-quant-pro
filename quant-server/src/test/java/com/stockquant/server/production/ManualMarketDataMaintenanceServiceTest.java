package com.stockquant.server.production;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Snapshot;
import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManualMarketDataMaintenanceServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate TARGET = LocalDate.of(2026, 10, 20);
    private static final Instant BEFORE_CUTOFF =
            Instant.parse("2026-10-21T03:00:00Z");
    private static final Instant AFTER_CUTOFF =
            Instant.parse("2026-10-21T08:30:00Z");
    private static final String REQUEST_ID =
            "SQHB_20261020T083000Z_123456789ABC";

    @Test
    void manualBeforeCutoffDispatchesResolvedPreviousTradeDate() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LocalDate> dispatchedDate = new AtomicReference<>();
        var service = service(BEFORE_CUTOFF, TARGET,
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    dispatchedDate.set(date);
                    return dispatched();
                }, ignored -> notRun(TARGET));

        Snapshot result = service.syncLatest();

        assertEquals(1, calls.get());
        assertEquals(TARGET, dispatchedDate.get());
        assertEquals(TARGET, result.tradeDate());
        assertEquals(Status.SUBMITTED, result.status());
        assertEquals(REQUEST_ID, result.requestId());
    }

    @Test
    void sameTargetRepeatedClickDoesNotDispatchSecondRequest() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Snapshot> state = new AtomicReference<>(
                notRun(TARGET));
        var service = service(BEFORE_CUTOFF, TARGET,
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    state.set(snapshot(Status.IN_PROGRESS, TARGET,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                            false));
                    return dispatched();
                }, ignored -> state.get());

        Snapshot first = service.syncLatest();
        Snapshot second = service.syncLatest();

        assertEquals(Status.IN_PROGRESS, first.status());
        assertEquals(Status.IN_PROGRESS, second.status());
        assertEquals(TARGET, first.tradeDate());
        assertEquals(1, calls.get());
    }

    @Test
    void processingStateIsReportedForResolvedTarget() {
        var service = service(BEFORE_CUTOFF, TARGET,
                failIfDispatched(), ignored -> snapshot(Status.IN_PROGRESS,
                        TARGET,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                        false));

        Snapshot result = service.status();

        assertEquals(Status.IN_PROGRESS, result.status());
        assertEquals(TARGET, result.tradeDate());
    }

    @Test
    void succeededReadyStateIsReportedWithoutDispatch() {
        Snapshot succeeded = new Snapshot(Status.SUCCEEDED, REQUEST_ID,
                TARGET, TARGET, "AUTOMATIC_MARKET_DATA_READY", true,
                TARGET, TARGET, List.of(), List.of(), 6);
        var service = service(BEFORE_CUTOFF, TARGET, failIfDispatched(),
                ignored -> succeeded);

        Snapshot result = service.syncLatest();

        assertEquals(Status.SUCCEEDED, result.status());
        assertTrue(result.readyForShadow());
        assertEquals(TARGET, result.latestCompleteTradeDate());
    }

    @Test
    void failedStatePreservesFormalReason() {
        Snapshot failed = snapshot(Status.FAILED, TARGET,
                "TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE", false);
        var service = service(BEFORE_CUTOFF, TARGET, failIfDispatched(),
                ignored -> failed);

        Snapshot result = service.status();

        assertEquals(Status.FAILED, result.status());
        assertEquals("TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE",
                result.reason());
        assertFalse(result.readyForShadow());
    }

    @Test
    void terminalResultIsReturnedWithoutAnotherSubmission() {
        AtomicInteger calls = new AtomicInteger();
        Snapshot terminal = snapshot(Status.FAILED, TARGET,
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED",
                false);
        var service = service(BEFORE_CUTOFF, TARGET,
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    return dispatched();
                }, ignored -> terminal);

        assertEquals(terminal, service.syncLatest());
        assertEquals(0, calls.get());
    }

    @Test
    void unavailableCalendarChecksStableIdentityThenFailsClosed() {
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicInteger queryCalls = new AtomicInteger();
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException(
                ManualMarketDataTargetResolver.TARGET_UNAVAILABLE));
        var service = service(BEFORE_CUTOFF,
                (date, submittedAt) -> {
                    dispatchCalls.incrementAndGet();
                    return dispatched();
                }, ignored -> {
                    queryCalls.incrementAndGet();
                    return notRun(TARGET);
                }, resolver);

        Snapshot result = service.syncLatest();

        assertEquals(Status.NOT_RUN, result.status());
        assertEquals("NONE", result.requestId());
        assertEquals(
                ManualMarketDataTargetResolver.TARGET_UNAVAILABLE,
                result.reason());
        assertEquals(0, dispatchCalls.get());
        assertEquals(1, queryCalls.get());
    }

    @Test
    void unavailableCalendarRecoversPersistedBootstrapStatus() {
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException(
                ManualMarketDataTargetResolver.TARGET_UNAVAILABLE));
        var service = service(BEFORE_CUTOFF, failIfDispatched(),
                date -> snapshot(Status.IN_PROGRESS, date,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                        false), resolver);

        Snapshot result = service.status();

        assertEquals(Status.IN_PROGRESS, result.status());
        assertEquals(TARGET, result.tradeDate());
        assertEquals(REQUEST_ID, result.requestId());
        assertNull(result.effectiveTargetTradeDate());
    }

    @Test
    void unavailableCalendarRecoversAcceptedRuntimeBeforeBrokerFileAppears() {
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException(
                ManualMarketDataTargetResolver.TARGET_UNAVAILABLE));
        var runtime = new AutomaticMarketDataMaintenanceRuntimeState();
        runtime.checked(BEFORE_CUTOFF, TARGET, REQUEST_ID,
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
        var service = service(BEFORE_CUTOFF, failIfDispatched(),
                ignored -> Snapshot.notRun(TARGET, null, REQUEST_ID,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN"),
                resolver, runtime);

        Snapshot result = service.status();

        assertEquals(Status.SUBMITTED, result.status());
        assertEquals(TARGET, result.tradeDate());
        assertEquals(REQUEST_ID, result.requestId());
        assertNull(result.effectiveTargetTradeDate());
    }

    @Test
    void networkFailureNeverFallsBackToPreviousTradeDate() {
        List<LocalDate> dispatchedDates = new ArrayList<>();
        AtomicInteger queries = new AtomicInteger();
        var service = service(AFTER_CUTOFF, TODAY,
                (date, submittedAt) -> {
                    dispatchedDates.add(date);
                    return dispatched();
                }, ignored -> queries.incrementAndGet() == 1
                        ? notRun(TODAY)
                        : snapshot(Status.FAILED, TODAY,
                        "TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE", false));

        Snapshot result = service.syncLatest();

        assertEquals(List.of(TODAY), dispatchedDates);
        assertEquals(2, queries.get());
        assertEquals(Status.FAILED, result.status());
        assertEquals(TODAY, result.tradeDate());
        assertEquals("TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE",
                result.reason());
    }

    @Test
    void staleAlignedCalendarDispatchesCoverageEndForBootstrap() {
        AtomicReference<LocalDate> dispatchedDate = new AtomicReference<>();
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenReturn(
                ManualMarketDataTargetResolver.Resolution.bootstrap(TODAY));
        var service = service(AFTER_CUTOFF,
                (date, submittedAt) -> {
                    dispatchedDate.set(date);
                    return dispatched();
                }, ignored -> Snapshot.notRun(TODAY, null, REQUEST_ID,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN"),
                resolver);

        Snapshot result = service.syncLatest();

        assertEquals(TODAY, dispatchedDate.get());
        assertEquals(TODAY, result.tradeDate());
        assertNull(result.effectiveTargetTradeDate());
        assertEquals(Status.SUBMITTED, result.status());
    }

    @Test
    void refreshedCalendarKeepsPollingBootstrapRequestIdentity() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        LocalDate friday = LocalDate.of(2026, 10, 23);
        Instant saturdayEvening = Instant.parse("2026-10-24T10:00:00Z");
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<Snapshot> broker = new AtomicReference<>(
                Snapshot.notRun(saturday, null, REQUEST_ID,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN"));
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenReturn(
                ManualMarketDataTargetResolver.Resolution.bootstrap(
                        saturday),
                ManualMarketDataTargetResolver.Resolution.known(
                        saturday, friday));
        var service = service(saturdayEvening,
                (date, submittedAt) -> {
                    dispatches.incrementAndGet();
                    broker.set(new Snapshot(Status.IN_PROGRESS, REQUEST_ID,
                            saturday, null,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                            false, null, null, null, null, null));
                    return dispatched();
                }, date -> date.equals(saturday) ? broker.get()
                        : notRun(date), resolver);

        Snapshot submitted = service.syncLatest();
        Snapshot polled = service.status();

        assertEquals(Status.IN_PROGRESS, submitted.status());
        assertEquals(Status.IN_PROGRESS, polled.status());
        assertEquals(saturday, polled.tradeDate());
        assertEquals(friday, polled.effectiveTargetTradeDate());
        assertEquals(1, dispatches.get());
    }

    @Test
    void coveredWeekendChecksBootstrapIdentityBeforeTradeDate() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        LocalDate friday = LocalDate.of(2026, 10, 23);
        List<LocalDate> queried = new ArrayList<>();
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenReturn(
                ManualMarketDataTargetResolver.Resolution.known(
                        saturday, friday));
        var service = service(Instant.parse("2026-10-24T10:00:00Z"),
                failIfDispatched(), date -> {
                    queried.add(date);
                    return date.equals(saturday) ? notRun(saturday)
                            : snapshot(Status.IN_PROGRESS, friday,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                            false);
                }, resolver);

        Snapshot result = service.status();

        assertEquals(List.of(saturday, friday), queried);
        assertEquals(Status.IN_PROGRESS, result.status());
        assertEquals(friday, result.tradeDate());
        assertEquals(friday, result.effectiveTargetTradeDate());
    }

    @Test
    void manualEntryPointHasNoSelectionShadowOrPaperDependency() {
        Constructor<?> constructor = Arrays.stream(
                        ManualMarketDataMaintenanceService.class
                                .getConstructors())
                .findFirst().orElseThrow();
        String dependencies = Arrays.toString(constructor.getParameterTypes());

        assertTrue(dependencies.contains("ManualMarketDataTargetResolver"));
        assertFalse(dependencies.contains("Selection"));
        assertFalse(dependencies.contains("Shadow"));
        assertFalse(dependencies.contains("Paper"));
    }

    private static ManualMarketDataMaintenanceService service(
            Instant instant,
            LocalDate target,
            AutomaticMarketDataMaintenanceDispatchGateway gateway,
            AutomaticMarketDataMaintenanceStatusQuery query
    ) {
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenReturn(
                ManualMarketDataTargetResolver.Resolution.known(target,
                        target));
        return service(instant, gateway, query, resolver);
    }

    private static ManualMarketDataMaintenanceService service(
            Instant instant,
            AutomaticMarketDataMaintenanceDispatchGateway gateway,
            AutomaticMarketDataMaintenanceStatusQuery query,
            ManualMarketDataTargetResolver resolver
    ) {
        return service(instant, gateway, query, resolver,
                new AutomaticMarketDataMaintenanceRuntimeState());
    }

    private static ManualMarketDataMaintenanceService service(
            Instant instant,
            AutomaticMarketDataMaintenanceDispatchGateway gateway,
            AutomaticMarketDataMaintenanceStatusQuery query,
            ManualMarketDataTargetResolver resolver,
            AutomaticMarketDataMaintenanceRuntimeState runtime
    ) {
        return new ManualMarketDataMaintenanceService(
                explicitManualGateway(gateway), query,
                enabledProperties(), Clock.fixed(instant, ZoneOffset.UTC),
                runtime, resolver);
    }

    private static AutomaticMarketDataMaintenanceDispatchGateway
    explicitManualGateway(
            AutomaticMarketDataMaintenanceDispatchGateway manualDelegate
    ) {
        return new AutomaticMarketDataMaintenanceDispatchGateway() {
            @Override
            public DispatchResult dispatch(
                    LocalDate tradeDate,
                    Instant submittedAt
            ) {
                throw new AssertionError(
                        "automatic dispatch must not be called");
            }

            @Override
            public DispatchResult dispatchManual(
                    LocalDate effectiveTargetTradeDate,
                    Instant submittedAt
            ) {
                return manualDelegate.dispatch(effectiveTargetTradeDate,
                        submittedAt);
            }
        };
    }

    private static AutomaticMarketDataMaintenanceDispatchGateway
    failIfDispatched() {
        return (date, submittedAt) -> {
            throw new AssertionError("dispatch must not be called");
        };
    }

    private static AutomaticMarketDataMaintenanceDispatchGateway
    .DispatchResult dispatched() {
        return new AutomaticMarketDataMaintenanceDispatchGateway
                .DispatchResult(REQUEST_ID, true,
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
    }

    private static Snapshot notRun(LocalDate target) {
        return Snapshot.notRun(target, REQUEST_ID,
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN");
    }

    private static Snapshot snapshot(
            Status status,
            LocalDate target,
            String reason,
            boolean ready
    ) {
        return new Snapshot(status, REQUEST_ID, target, target, reason,
                ready, null, null, null, null, null);
    }

    private static AutomaticMarketDataMaintenanceProperties
    enabledProperties() {
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        return properties;
    }
}
