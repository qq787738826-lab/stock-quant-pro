package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareTokenRateLimiterTest {

    @Test
    void sharesOneSlidingWindowAcrossAllEndpointsInOneProcess() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        List<Duration> waits = new ArrayList<>();
        TushareTokenRateLimiter limiter = limiter(
                2, 10, now, date, duration -> {
                    waits.add(duration);
                    now.addAndGet(duration.toNanos());
                });

        limiter.acquire("daily");
        limiter.acquire("adj_factor");
        limiter.acquire("trade_cal");

        var snapshot = limiter.snapshot();
        assertEquals(3, snapshot.totalCallCount());
        assertEquals(1, snapshot.currentGlobalWindowCallCount());
        assertEquals(2, snapshot.globalSafeLimitPerMinute());
        assertEquals(10, snapshot.dailySafeLimitPerApi());
        assertEquals(Duration.ofMinutes(1), snapshot.window());
        assertEquals(
                java.util.Map.of(
                        "daily", 1L,
                        "adj_factor", 1L,
                        "trade_cal", 1L),
                snapshot.endpointCallCounts());
        assertEquals(snapshot.endpointCallCounts(),
                snapshot.dailyEndpointCallCounts());
        assertEquals(List.of(Duration.ofMinutes(1)), waits);
    }

    @Test
    void dailyPerEndpointBudgetHardStopsAndResetsOnNextDate() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        TushareTokenRateLimiter limiter = limiter(
                10, 2, now, date,
                duration -> now.addAndGet(duration.toNanos()));

        limiter.acquire("daily");
        limiter.acquire("daily");
        TushareTokenRateLimiter.QuotaException error = assertThrows(
                TushareTokenRateLimiter.QuotaException.class,
                () -> limiter.acquire("daily"));
        assertEquals("TUSHARE_DAILY_API_BUDGET_EXHAUSTED",
                error.safeCode());
        assertEquals(2, limiter.snapshot().totalCallCount());

        limiter.acquire("adj_factor");
        date.set(LocalDate.of(2026, 7, 31));
        limiter.acquire("daily");
        assertEquals(
                java.util.Map.of("daily", 1L),
                limiter.snapshot().dailyEndpointCallCounts());
    }

    @Test
    void concurrentCallersCannotBypassTheSharedMinuteBudget()
            throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        TushareTokenRateLimiter limiter = limiter(
                1, 100, now, date,
                duration -> now.addAndGet(duration.toNanos()));
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(callers);
        try {
            List<java.util.concurrent.Future<?>> futures =
                    new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                int call = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    limiter.acquire(call % 2 == 0
                            ? "daily" : "trade_cal");
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(
                    5, TimeUnit.SECONDS));
        }
        var snapshot = limiter.snapshot();
        assertEquals(callers, snapshot.totalCallCount());
        assertTrue(snapshot.currentGlobalWindowCallCount() <= 1);
        assertEquals(callers,
                snapshot.endpointCallCounts().values().stream()
                        .mapToLong(Long::longValue).sum());
    }

    @Test
    void frozenPolicyUsesConservativeEndpointMinimums() {
        TushareEndpointRateLimitPolicy policy =
                TushareEndpointRateLimitPolicy.frozenF1cPolicy();

        assertEquals(180, policy.globalSafeLimitPerMinute());
        assertEquals(90_000, policy.dailySafeLimitPerEndpoint());
        assertEquals(Map.of(
                        "stock_basic", 45,
                        "daily", 180,
                        "adj_factor", 180,
                        "trade_cal", 180,
                        "dividend", 180),
                policy.endpointSafeLimitsPerMinute());
        assertEquals(
                50,
                TushareEndpointRateLimitPolicy.Endpoint.STOCK_BASIC
                        .effectiveOfficialLimitPerMinute());
        assertEquals(
                200,
                TushareEndpointRateLimitPolicy.Endpoint.DAILY
                        .effectiveOfficialLimitPerMinute());
        assertTrue(policy.conservativeMinimumPolicyEnforced());
    }

    @Test
    void endpointExhaustionDoesNotReserveGlobalSlotBeforeWaiting() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        AtomicInteger waits = new AtomicInteger();
        List<Long> totalsSeenWhileWaiting = new ArrayList<>();
        AtomicReference<TushareTokenRateLimiter> reference =
                new AtomicReference<>();
        TushareEndpointRateLimitPolicy policy = policy(
                3, 100, Map.of(
                        TushareEndpointRateLimitPolicy.Endpoint.STOCK_BASIC,
                        1,
                        TushareEndpointRateLimitPolicy.Endpoint.DAILY,
                        3,
                        TushareEndpointRateLimitPolicy.Endpoint.ADJ_FACTOR,
                        3,
                        TushareEndpointRateLimitPolicy.Endpoint.TRADE_CAL,
                        3,
                        TushareEndpointRateLimitPolicy.Endpoint.DIVIDEND,
                        3));
        TushareTokenRateLimiter limiter =
                new TushareTokenRateLimiter(
                        policy,
                        Duration.ofMinutes(1),
                        now::get,
                        date::get,
                        duration -> {
                            waits.incrementAndGet();
                            totalsSeenWhileWaiting.add(
                                    reference.get().snapshot()
                                            .totalCallCount());
                            now.addAndGet(duration.toNanos());
                        });
        reference.set(limiter);

        limiter.acquire("stock_basic");
        limiter.acquire("daily");
        limiter.acquire("stock_basic");

        assertEquals(1, waits.get());
        assertEquals(List.of(2L), totalsSeenWhileWaiting);
        assertEquals(3, limiter.snapshot().totalCallCount());
        assertEquals(1, limiter.snapshot()
                .currentEndpointWindowCallCounts()
                .get("stock_basic"));
    }

    @Test
    void endpointMinuteWindowsAreIndependentButGlobalStillApplies() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        List<Duration> waits = new ArrayList<>();
        TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(
                policy(2, 100, Map.of(
                        TushareEndpointRateLimitPolicy.Endpoint.STOCK_BASIC,
                        1,
                        TushareEndpointRateLimitPolicy.Endpoint.DAILY,
                        2,
                        TushareEndpointRateLimitPolicy.Endpoint.ADJ_FACTOR,
                        2,
                        TushareEndpointRateLimitPolicy.Endpoint.TRADE_CAL,
                        2,
                        TushareEndpointRateLimitPolicy.Endpoint.DIVIDEND,
                        2)),
                Duration.ofMinutes(1),
                now::get,
                date::get,
                duration -> {
                    waits.add(duration);
                    now.addAndGet(duration.toNanos());
                });

        limiter.acquire("stock_basic");
        limiter.acquire("daily");
        assertTrue(waits.isEmpty());
        limiter.acquire("adj_factor");

        assertEquals(List.of(Duration.ofMinutes(1)), waits);
        assertEquals(Map.of(
                        "stock_basic", 1L,
                        "daily", 1L,
                        "adj_factor", 1L),
                limiter.snapshot().endpointCallCounts());
    }

    @Test
    void unknownEndpointIsRejectedWithoutAnyRegistration() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        TushareTokenRateLimiter limiter = limiter(
                10, 10, now, date,
                duration -> now.addAndGet(duration.toNanos()));

        TushareTokenRateLimiter.QuotaException error = assertThrows(
                TushareTokenRateLimiter.QuotaException.class,
                () -> limiter.acquire("unknown_endpoint"));

        assertEquals("TUSHARE_ENDPOINT_NOT_ALLOWED", error.safeCode());
        assertEquals(0, limiter.snapshot().totalCallCount());
        assertTrue(limiter.snapshot().endpointCallCounts().isEmpty());
    }

    @Test
    void retryAttemptsConsumeTheSameEndpointAndGlobalBudgets() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        TushareTokenRateLimiter limiter = limiter(
                10, 10, now, date,
                duration -> now.addAndGet(duration.toNanos()));

        limiter.acquire("daily");
        limiter.acquire("daily");
        limiter.acquire("daily");

        var snapshot = limiter.snapshot();
        assertEquals(3, snapshot.totalCallCount());
        assertEquals(3L, snapshot.endpointCallCounts().get("daily"));
        assertEquals(3, snapshot.currentGlobalWindowCallCount());
        assertEquals(3, snapshot.currentEndpointWindowCallCounts()
                .get("daily"));
    }

    @Test
    void snapshotIsTypedProcessOnlyAndContainsNoTokenField() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        TushareTokenRateLimiter limiter = limiter(
                10, 10, now, date,
                duration -> now.addAndGet(duration.toNanos()));
        limiter.acquire("trade_cal");

        var snapshot = limiter.snapshot();

        assertFalse(snapshot.distributedCoordination());
        assertTrue(java.util.Arrays.stream(
                        snapshot.getClass().getRecordComponents())
                .noneMatch(component -> component.getName()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("token")));
        assertFalse(snapshot.toString().toLowerCase(
                        java.util.Locale.ROOT)
                .contains("token"));
    }

    @Test
    void interruptionStopsBeforeGrantingAnotherCall() {
        AtomicLong now = new AtomicLong();
        AtomicReference<LocalDate> date =
                new AtomicReference<>(LocalDate.of(2026, 7, 30));
        TushareTokenRateLimiter limiter = limiter(
                1, 100, now, date, duration -> {
                    throw new InterruptedException("test");
                });
        limiter.acquire("daily");
        TushareTokenRateLimiter.QuotaException error = assertThrows(
                TushareTokenRateLimiter.QuotaException.class,
                () -> limiter.acquire("daily"));
        assertEquals("TUSHARE_RATE_LIMIT_WAIT_INTERRUPTED",
                error.safeCode());
        assertEquals(1, limiter.snapshot().totalCallCount());
        Thread.interrupted();
    }

    private static TushareTokenRateLimiter limiter(
            int minuteLimit,
            int dailyLimit,
            AtomicLong now,
            AtomicReference<LocalDate> date,
            TushareTokenRateLimiter.WaitStrategy wait
    ) {
        return new TushareTokenRateLimiter(
                minuteLimit,
                dailyLimit,
                Duration.ofMinutes(1),
                now::get,
                date::get,
                wait);
    }

    private static TushareEndpointRateLimitPolicy policy(
            int globalLimit,
            int dailyLimit,
            Map<TushareEndpointRateLimitPolicy.Endpoint, Integer>
                    endpointLimits
    ) {
        Map<TushareEndpointRateLimitPolicy.Endpoint, Integer> values =
                new EnumMap<>(
                        TushareEndpointRateLimitPolicy.Endpoint.class);
        values.putAll(endpointLimits);
        return new TushareEndpointRateLimitPolicy(
                globalLimit, dailyLimit, values);
    }
}
