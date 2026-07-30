package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareTokenRateLimiterTest {

    @Test
    void sharesOneSlidingWindowAcrossAllEndpoints() {
        AtomicLong now = new AtomicLong();
        List<Duration> waits = new ArrayList<>();
        TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(
                2,
                Duration.ofMinutes(1),
                now::get,
                duration -> {
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
        assertEquals(Duration.ofMinutes(1), snapshot.window());
        assertEquals(
                java.util.Map.of(
                        "daily", 1L,
                        "adj_factor", 1L,
                        "trade_cal", 1L),
                snapshot.endpointCallCounts());
        assertEquals(List.of(Duration.ofMinutes(1)), waits);
    }

    @Test
    void concurrentCallersCannotBypassTheSharedBudget()
            throws Exception {
        AtomicLong now = new AtomicLong();
        TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(
                1,
                Duration.ofMillis(10),
                now::get,
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
        TushareTokenRateLimiter limiter = new TushareTokenRateLimiter(
                1,
                Duration.ofMinutes(1),
                now::get,
                duration -> {
                    throw new InterruptedException("test");
                });
        limiter.acquire("daily");
        assertThrows(IllegalStateException.class,
                () -> limiter.acquire("daily"));
        assertEquals(1, limiter.snapshot().totalCallCount());
        Thread.interrupted();
    }
}
