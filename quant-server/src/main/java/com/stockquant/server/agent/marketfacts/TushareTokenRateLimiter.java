package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One process-wide sliding-window and per-endpoint daily limiter shared by
 * every in-process Tushare caller. It does not coordinate other processes.
 */
@Component
public final class TushareTokenRateLimiter {

    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Shanghai");

    private final Object monitor = new Object();
    private final int minuteSafeLimit;
    private final int dailySafeLimitPerApi;
    private final Duration window;
    private final long windowNanos;
    private final NanoTimeSource nanoTimeSource;
    private final DateSource dateSource;
    private final WaitStrategy waitStrategy;
    private final ArrayDeque<Long> grantedAt = new ArrayDeque<>();
    private final AtomicLong totalCallCount = new AtomicLong();
    private final Map<String, Long> endpointCallCounts =
            new LinkedHashMap<>();
    private final Map<String, Long> dailyEndpointCallCounts =
            new LinkedHashMap<>();
    private LocalDate dailyCountDate;

    @Autowired
    public TushareTokenRateLimiter(
            TushareMarketFactProperties properties
    ) {
        properties.validateFrozenContract();
        this.minuteSafeLimit =
                properties.getApplicationSafeLimitPerMinute();
        this.dailySafeLimitPerApi =
                properties.getApplicationDailySafeLimitPerApi();
        this.window = DEFAULT_WINDOW;
        this.windowNanos = window.toNanos();
        this.nanoTimeSource = System::nanoTime;
        this.dateSource = () -> LocalDate.now(PROVIDER_ZONE);
        this.waitStrategy = duration ->
                Thread.sleep(duration.toMillis(),
                        duration.minusMillis(duration.toMillis()).getNano());
        this.dailyCountDate = dateSource.currentDate();
    }

    TushareTokenRateLimiter(
            int minuteSafeLimit,
            int dailySafeLimitPerApi,
            Duration window,
            NanoTimeSource nanoTimeSource,
            DateSource dateSource,
            WaitStrategy waitStrategy
    ) {
        if (minuteSafeLimit <= 0) {
            throw new IllegalArgumentException("minuteSafeLimit");
        }
        if (dailySafeLimitPerApi <= 0) {
            throw new IllegalArgumentException("dailySafeLimitPerApi");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window");
        }
        this.minuteSafeLimit = minuteSafeLimit;
        this.dailySafeLimitPerApi = dailySafeLimitPerApi;
        this.window = window;
        this.windowNanos = window.toNanos();
        this.nanoTimeSource = Objects.requireNonNull(
                nanoTimeSource, "nanoTimeSource");
        this.dateSource = Objects.requireNonNull(
                dateSource, "dateSource");
        this.waitStrategy = Objects.requireNonNull(
                waitStrategy, "waitStrategy");
        this.dailyCountDate = Objects.requireNonNull(
                dateSource.currentDate(), "currentDate");
    }

    /**
     * Grants one in-process API call. Minute exhaustion waits for the next
     * sliding-window slot; daily exhaustion fails immediately.
     */
    public void acquire(String endpoint) {
        validateEndpoint(endpoint);
        while (true) {
            long waitNanos;
            synchronized (monitor) {
                rotateDailyCounts();
                long dailyCount = dailyEndpointCallCounts
                        .getOrDefault(endpoint, 0L);
                if (dailyCount >= dailySafeLimitPerApi) {
                    throw new QuotaException(
                            "TUSHARE_DAILY_API_BUDGET_EXHAUSTED");
                }
                long now = nanoTimeSource.nanoTime();
                evictExpired(now);
                if (grantedAt.size() < minuteSafeLimit) {
                    grantedAt.addLast(now);
                    totalCallCount.incrementAndGet();
                    endpointCallCounts.merge(endpoint, 1L, Long::sum);
                    dailyEndpointCallCounts.merge(
                            endpoint, 1L, Long::sum);
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
                throw new QuotaException(
                        "TUSHARE_RATE_LIMIT_WAIT_INTERRUPTED", error);
            }
        }
    }

    public RateLimitSnapshot snapshot() {
        synchronized (monitor) {
            rotateDailyCounts();
            evictExpired(nanoTimeSource.nanoTime());
            return new RateLimitSnapshot(
                    minuteSafeLimit,
                    dailySafeLimitPerApi,
                    window,
                    totalCallCount.get(),
                    grantedAt.size(),
                    dailyCountDate,
                    Map.copyOf(endpointCallCounts),
                    Map.copyOf(dailyEndpointCallCounts));
        }
    }

    private void rotateDailyCounts() {
        LocalDate current = Objects.requireNonNull(
                dateSource.currentDate(), "currentDate");
        if (!current.equals(dailyCountDate)) {
            dailyEndpointCallCounts.clear();
            dailyCountDate = current;
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

    private static void validateEndpoint(String endpoint) {
        if (endpoint == null
                || !endpoint.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException(
                    "invalid Tushare endpoint");
        }
    }

    public record RateLimitSnapshot(
            int safeLimitPerMinute,
            int dailySafeLimitPerApi,
            Duration window,
            long totalCallCount,
            int currentWindowCallCount,
            LocalDate dailyCountDate,
            Map<String, Long> endpointCallCounts,
            Map<String, Long> dailyEndpointCallCounts
    ) {
        public RateLimitSnapshot {
            endpointCallCounts = Map.copyOf(endpointCallCounts);
            dailyEndpointCallCounts =
                    Map.copyOf(dailyEndpointCallCounts);
        }
    }

    static final class QuotaException extends RuntimeException {
        private final String safeCode;

        QuotaException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        QuotaException(String safeCode, Throwable cause) {
            super(safeCode, cause);
            this.safeCode = safeCode;
        }

        String safeCode() {
            return safeCode;
        }
    }

    @FunctionalInterface
    interface NanoTimeSource {
        long nanoTime();
    }

    @FunctionalInterface
    interface DateSource {
        LocalDate currentDate();
    }

    @FunctionalInterface
    interface WaitStrategy {
        void await(Duration duration) throws InterruptedException;
    }
}
