package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(1, snapshot.currentWindowCallCount());
        assertEquals(2, snapshot.safeLimitPerMinute());
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
        assertTrue(snapshot.currentWindowCallCount() <= 1);
        assertEquals(callers,
                snapshot.endpointCallCounts().values().stream()
                        .mapToLong(Long::longValue).sum());
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
}
