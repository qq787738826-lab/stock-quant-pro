package com.stockquant.server.production;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Snapshot;
import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualMarketDataMaintenanceServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 21);
    private static final Instant INSIDE_WINDOW =
            Instant.parse("2026-10-21T08:30:00Z");
    private static final Instant OUTSIDE_WINDOW =
            Instant.parse("2026-10-21T03:00:00Z");
    private static final String REQUEST_ID =
            "SQHB_20261021T083000Z_123456789ABC";

    @Test
    void insideWindowDispatchesExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(INSIDE_WINDOW,
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    return dispatched();
                }, ignored -> notRun());

        Snapshot result = service.syncLatest();

        assertEquals(1, calls.get());
        assertEquals(Status.SUBMITTED, result.status());
        assertEquals(REQUEST_ID, result.requestId());
    }

    @Test
    void outsideWindowRejectsBeforeStatusLookupOrDispatch() {
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicInteger queryCalls = new AtomicInteger();
        var service = service(OUTSIDE_WINDOW,
                (date, submittedAt) -> {
                    dispatchCalls.incrementAndGet();
                    return dispatched();
                }, ignored -> {
                    queryCalls.incrementAndGet();
                    return notRun();
                });

        Snapshot result = service.syncLatest();

        assertEquals(Status.NOT_RUN, result.status());
        assertEquals("NONE", result.requestId());
        assertEquals(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_WINDOW_INVALID",
                result.reason());
        assertEquals(0, dispatchCalls.get());
        assertEquals(0, queryCalls.get());
    }

    @Test
    void weekendRejectsBeforeStatusLookupOrDispatch() {
        AtomicInteger dispatchCalls = new AtomicInteger();
        AtomicInteger queryCalls = new AtomicInteger();
        var service = service(Instant.parse("2026-10-24T08:30:00Z"),
                (date, submittedAt) -> {
                    dispatchCalls.incrementAndGet();
                    return dispatched();
                }, ignored -> {
                    queryCalls.incrementAndGet();
                    return notRun();
                });

        Snapshot result = service.syncLatest();

        assertEquals(Status.NOT_RUN, result.status());
        assertEquals(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_WINDOW_INVALID",
                result.reason());
        assertEquals(0, dispatchCalls.get());
        assertEquals(0, queryCalls.get());
    }

    @Test
    void sameDateRepeatedClickDoesNotDispatchSecondRequest() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Snapshot> state = new AtomicReference<>(notRun());
        var service = service(INSIDE_WINDOW,
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    state.set(snapshot(Status.IN_PROGRESS,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                            false));
                    return dispatched();
                }, ignored -> state.get());

        Snapshot first = service.syncLatest();
        Snapshot second = service.syncLatest();

        assertEquals(Status.IN_PROGRESS, first.status());
        assertEquals(Status.IN_PROGRESS, second.status());
        assertEquals(1, calls.get());
    }

    @Test
    void processingStateIsReportedAsInProgress() {
        var service = service(INSIDE_WINDOW,
                failIfDispatched(), ignored -> snapshot(Status.IN_PROGRESS,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS",
                        false));

        assertEquals(Status.IN_PROGRESS, service.status().status());
    }

    @Test
    void succeededReadyStateIsReportedWithoutDispatch() {
        Snapshot succeeded = new Snapshot(Status.SUCCEEDED, REQUEST_ID,
                DATE, "AUTOMATIC_MARKET_DATA_READY", true, DATE, DATE,
                List.of(), List.of(), 6);
        var service = service(INSIDE_WINDOW, failIfDispatched(),
                ignored -> succeeded);

        Snapshot result = service.syncLatest();

        assertEquals(Status.SUCCEEDED, result.status());
        assertTrue(result.readyForShadow());
        assertEquals(DATE, result.latestCompleteTradeDate());
    }

    @Test
    void failedStatePreservesFormalReason() {
        Snapshot failed = snapshot(Status.FAILED,
                "TUSHARE_CONNECT_TIMEOUT_NO_RESPONSE", false);
        var service = service(INSIDE_WINDOW, failIfDispatched(),
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
        Snapshot terminal = snapshot(Status.FAILED,
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_BUDGET_EXHAUSTED",
                false);
        var service = service(INSIDE_WINDOW,
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    return dispatched();
                }, ignored -> terminal);

        assertEquals(terminal, service.syncLatest());
        assertEquals(0, calls.get());
    }

    @Test
    void safeWindowBoundariesRemainInclusive() {
        AtomicInteger calls = new AtomicInteger();
        var gateway = countingGateway(calls);

        service(Instant.parse("2026-10-21T08:25:00Z"), gateway,
                ignored -> notRun()).syncLatest();
        service(Instant.parse("2026-10-21T09:10:00Z"), gateway,
                ignored -> notRun()).syncLatest();

        assertEquals(2, calls.get());
    }

    @Test
    void manualEntryPointHasNoSelectionShadowOrPaperDependency() {
        Constructor<?> constructor = Arrays.stream(
                        ManualMarketDataMaintenanceService.class
                                .getConstructors())
                .findFirst().orElseThrow();
        String dependencies = Arrays.toString(constructor.getParameterTypes());

        assertFalse(dependencies.contains("Selection"));
        assertFalse(dependencies.contains("Shadow"));
        assertFalse(dependencies.contains("Paper"));
    }

    private static ManualMarketDataMaintenanceService service(
            Instant instant,
            AutomaticMarketDataMaintenanceDispatchGateway gateway,
            AutomaticMarketDataMaintenanceStatusQuery query
    ) {
        return new ManualMarketDataMaintenanceService(gateway, query,
                enabledProperties(), Clock.fixed(instant, ZoneOffset.UTC),
                new AutomaticMarketDataMaintenanceRuntimeState());
    }

    private static AutomaticMarketDataMaintenanceDispatchGateway
    countingGateway(AtomicInteger calls) {
        return (date, submittedAt) -> {
            calls.incrementAndGet();
            return dispatched();
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

    private static Snapshot notRun() {
        return Snapshot.notRun(DATE, REQUEST_ID,
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN");
    }

    private static Snapshot snapshot(
            Status status,
            String reason,
            boolean ready
    ) {
        return new Snapshot(status, REQUEST_ID, DATE, reason, ready,
                null, null, null, null, null);
    }

    private static AutomaticMarketDataMaintenanceProperties
    enabledProperties() {
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        return properties;
    }
}
