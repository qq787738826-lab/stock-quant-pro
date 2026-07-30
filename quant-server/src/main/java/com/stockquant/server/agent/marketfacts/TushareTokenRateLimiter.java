package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic process-wide, endpoint-minute and endpoint-daily limiter shared by
 * every in-process Tushare caller. It does not coordinate other processes.
 */
@Component
public final class TushareTokenRateLimiter {

    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
    static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Shanghai");

    private final Object monitor = new Object();
    private final TushareEndpointRateLimitPolicy policy;
    private final Duration window;
    private final long windowNanos;
    private final NanoTimeSource nanoTimeSource;
    private final DateSource dateSource;
    private final WaitStrategy waitStrategy;
    private final ArrayDeque<Long> globalGrantedAt = new ArrayDeque<>();
    private final Map<TushareEndpointRateLimitPolicy.Endpoint,
            ArrayDeque<Long>> endpointGrantedAt =
            new EnumMap<>(TushareEndpointRateLimitPolicy.Endpoint.class);
    private final AtomicLong totalCallCount = new AtomicLong();
    private final Map<String, Long> endpointCallCounts =
            new LinkedHashMap<>();
    private final Map<String, Long> dailyEndpointCallCounts =
            new LinkedHashMap<>();
    private LocalDate dailyCountDate;

    @Autowired
    public TushareTokenRateLimiter(
            TushareEndpointRateLimitPolicy policy
    ) {
        this(
                policy,
                DEFAULT_WINDOW,
                System::nanoTime,
                () -> LocalDate.now(PROVIDER_ZONE),
                duration -> Thread.sleep(
                        duration.toMillis(),
                        duration.minusMillis(
                                duration.toMillis()).getNano()));
    }

    public TushareTokenRateLimiter(
            TushareMarketFactProperties properties
    ) {
        this(new TushareEndpointRateLimitPolicy(properties));
    }

    TushareTokenRateLimiter(
            int minuteSafeLimit,
            int dailySafeLimitPerApi,
            Duration window,
            NanoTimeSource nanoTimeSource,
            DateSource dateSource,
            WaitStrategy waitStrategy
    ) {
        this(
                testPolicy(minuteSafeLimit, dailySafeLimitPerApi),
                window,
                nanoTimeSource,
                dateSource,
                waitStrategy);
    }

    TushareTokenRateLimiter(
            TushareEndpointRateLimitPolicy policy,
            Duration window,
            NanoTimeSource nanoTimeSource,
            DateSource dateSource,
            WaitStrategy waitStrategy
    ) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window");
        }
        this.policy = Objects.requireNonNull(policy, "policy");
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
        TushareEndpointRateLimitPolicy.Endpoint.stream()
                .forEach(endpoint -> endpointGrantedAt.put(
                        endpoint, new ArrayDeque<>()));
    }

    /**
     * Grants one in-process API call. Minute exhaustion waits for the next
     * sliding-window slot; daily exhaustion fails immediately.
     */
    public void acquire(String endpoint) {
        TushareEndpointRateLimitPolicy.Endpoint typedEndpoint =
                policy.endpoint(endpoint).orElseThrow(() ->
                        new QuotaException(
                                "TUSHARE_ENDPOINT_NOT_ALLOWED"));
        while (true) {
            long waitNanos;
            synchronized (monitor) {
                rotateDailyCounts();
                long dailyCount = dailyEndpointCallCounts
                        .getOrDefault(endpoint, 0L);
                if (dailyCount >= policy.dailySafeLimitPerEndpoint()) {
                    throw new QuotaException(
                            "TUSHARE_DAILY_API_BUDGET_EXHAUSTED");
                }
                long now = nanoTimeSource.nanoTime();
                evictExpired(globalGrantedAt, now);
                ArrayDeque<Long> endpointWindow =
                        endpointGrantedAt.get(typedEndpoint);
                if (endpointWindow == null) {
                    throw new QuotaException(
                            "TUSHARE_ENDPOINT_RATE_POLICY_INVALID");
                }
                evictExpired(endpointWindow, now);
                boolean globalAvailable =
                        globalGrantedAt.size()
                                < policy.globalSafeLimitPerMinute();
                boolean endpointAvailable =
                        endpointWindow.size()
                                < policy.safeLimitPerMinute(typedEndpoint);
                if (globalAvailable && endpointAvailable) {
                    globalGrantedAt.addLast(now);
                    endpointWindow.addLast(now);
                    totalCallCount.incrementAndGet();
                    endpointCallCounts.merge(endpoint, 1L, Long::sum);
                    dailyEndpointCallCounts.merge(
                            endpoint, 1L, Long::sum);
                    return;
                }
                long globalWait = globalAvailable
                        ? 0L : waitFor(globalGrantedAt, now);
                long endpointWait = endpointAvailable
                        ? 0L : waitFor(endpointWindow, now);
                waitNanos = Math.max(
                        1L, Math.max(globalWait, endpointWait));
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
            long now = nanoTimeSource.nanoTime();
            evictExpired(globalGrantedAt, now);
            endpointGrantedAt.values().forEach(
                    values -> evictExpired(values, now));
            Map<String, Integer> currentEndpointWindows =
                    new LinkedHashMap<>();
            TushareEndpointRateLimitPolicy.Endpoint.stream()
                    .forEach(endpoint -> currentEndpointWindows.put(
                            endpoint.providerName(),
                            endpointGrantedAt.get(endpoint).size()));
            return new RateLimitSnapshot(
                    policy.globalSafeLimitPerMinute(),
                    globalGrantedAt.size(),
                    policy.endpointSafeLimitsPerMinute(),
                    currentEndpointWindows,
                    policy.dailySafeLimitPerEndpoint(),
                    Map.copyOf(dailyEndpointCallCounts),
                    totalCallCount.get(),
                    Map.copyOf(endpointCallCounts),
                    window,
                    dailyCountDate,
                    false);
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

    private void evictExpired(ArrayDeque<Long> values, long now) {
        while (!values.isEmpty()
                && elapsed(values.getFirst(), now) >= windowNanos) {
            values.removeFirst();
        }
    }

    private long waitFor(ArrayDeque<Long> values, long now) {
        if (values.isEmpty()) {
            throw new QuotaException(
                    "TUSHARE_ENDPOINT_RATE_POLICY_INVALID");
        }
        return Math.max(
                1L,
                windowNanos - elapsed(values.getFirst(), now));
    }

    private static long elapsed(long start, long end) {
        long elapsed = end - start;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    private static TushareEndpointRateLimitPolicy testPolicy(
            int minuteSafeLimit,
            int dailySafeLimitPerApi
    ) {
        Map<TushareEndpointRateLimitPolicy.Endpoint, Integer> limits =
                new EnumMap<>(
                        TushareEndpointRateLimitPolicy.Endpoint.class);
        TushareEndpointRateLimitPolicy.Endpoint.stream()
                .forEach(endpoint -> limits.put(
                        endpoint, minuteSafeLimit));
        return new TushareEndpointRateLimitPolicy(
                minuteSafeLimit,
                dailySafeLimitPerApi,
                limits);
    }

    public record RateLimitSnapshot(
            int globalSafeLimitPerMinute,
            int currentGlobalWindowCallCount,
            Map<String, Integer> endpointSafeLimitsPerMinute,
            Map<String, Integer> currentEndpointWindowCallCounts,
            int dailySafeLimitPerApi,
            Map<String, Long> dailyEndpointCallCounts,
            long totalCallCount,
            Map<String, Long> endpointCallCounts,
            Duration window,
            LocalDate dailyCountDate,
            boolean distributedCoordination
    ) {
        public RateLimitSnapshot {
            endpointSafeLimitsPerMinute =
                    Map.copyOf(endpointSafeLimitsPerMinute);
            currentEndpointWindowCallCounts =
                    Map.copyOf(currentEndpointWindowCallCounts);
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
