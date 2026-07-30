package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One process-wide, token-level sliding-window limiter shared by every
 * Tushare endpoint and caller.
 */
@Component
public final class TushareTokenRateLimiter {

    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    private final Object monitor = new Object();
    private final int safeLimit;
    private final Duration window;
    private final long windowNanos;
    private final NanoTimeSource nanoTimeSource;
    private final WaitStrategy waitStrategy;
    private final ArrayDeque<Long> grantedAt = new ArrayDeque<>();
    private final AtomicLong totalCallCount = new AtomicLong();
    private final Map<String, Long> endpointCallCounts =
            new LinkedHashMap<>();

    @Autowired
    public TushareTokenRateLimiter(
            TushareMarketFactProperties properties
    ) {
        properties.validateRateLimits();
        this.safeLimit = properties.getApplicationSafeLimitPerMinute();
        this.window = DEFAULT_WINDOW;
        this.windowNanos = window.toNanos();
        this.nanoTimeSource = System::nanoTime;
        this.waitStrategy = duration ->
                Thread.sleep(duration.toMillis(),
                        duration.minusMillis(duration.toMillis()).getNano());
    }

    TushareTokenRateLimiter(
            int safeLimit,
            Duration window,
            NanoTimeSource nanoTimeSource,
            WaitStrategy waitStrategy
    ) {
        if (safeLimit <= 0) {
            throw new IllegalArgumentException("safeLimit");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window");
        }
        this.safeLimit = safeLimit;
        this.window = window;
        this.windowNanos = window.toNanos();
        this.nanoTimeSource = Objects.requireNonNull(
                nanoTimeSource, "nanoTimeSource");
        this.waitStrategy = Objects.requireNonNull(
                waitStrategy, "waitStrategy");
    }

    public void acquire(String endpoint) {
        if (endpoint == null || !endpoint.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("invalid Tushare endpoint");
        }
        while (true) {
            long waitNanos;
            synchronized (monitor) {
                long now = nanoTimeSource.nanoTime();
                evictExpired(now);
                if (grantedAt.size() < safeLimit) {
                    grantedAt.addLast(now);
                    totalCallCount.incrementAndGet();
                    endpointCallCounts.merge(endpoint, 1L, Long::sum);
                    return;
                }
                long oldest = grantedAt.getFirst();
                waitNanos = Math.max(
                        1L, windowNanos - elapsed(oldest, now));
            }
            try {
                waitStrategy.await(Duration.ofNanos(waitNanos));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "TUSHARE_RATE_LIMIT_WAIT_INTERRUPTED", error);
            }
        }
    }

    public RateLimitSnapshot snapshot() {
        synchronized (monitor) {
            evictExpired(nanoTimeSource.nanoTime());
            return new RateLimitSnapshot(
                    safeLimit,
                    window,
                    totalCallCount.get(),
                    grantedAt.size(),
                    Map.copyOf(endpointCallCounts));
        }
    }

    private void evictExpired(long now) {
        while (!grantedAt.isEmpty()
                && elapsed(grantedAt.getFirst(), now) >= windowNanos) {
            grantedAt.removeFirst();
        }
    }

    private static long elapsed(long start, long end) {
        long elapsed = end - start;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    public record RateLimitSnapshot(
            int safeLimitPerMinute,
            Duration window,
            long totalCallCount,
            int currentWindowCallCount,
            Map<String, Long> endpointCallCounts
    ) {
        public RateLimitSnapshot {
            endpointCallCounts = Map.copyOf(endpointCallCounts);
        }
    }

    @FunctionalInterface
    interface NanoTimeSource {
        long nanoTime();
    }

    @FunctionalInterface
    interface WaitStrategy {
        void await(Duration duration) throws InterruptedException;
    }
}
