package com.stockquant.server.agent.marketfacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed F1C rate-limit policy for the five permitted Tushare endpoints.
 *
 * <p>The effective official limit is the most conservative minimum of the
 * general 2000-point limit and any endpoint-specific limit. Unknown
 * endpoints never inherit a generic fallback.
 */
@Component
public final class TushareEndpointRateLimitPolicy {

    public static final int GLOBAL_SAFE_LIMIT_PER_MINUTE = 180;
    public static final int DAILY_SAFE_LIMIT_PER_ENDPOINT = 90_000;

    private final int globalSafeLimitPerMinute;
    private final int dailySafeLimitPerEndpoint;
    private final Map<Endpoint, Integer> endpointSafeLimitsPerMinute;
    private final boolean frozenF1cPolicy;

    @Autowired
    public TushareEndpointRateLimitPolicy(
            TushareMarketFactProperties properties
    ) {
        this(
                validatedGlobalLimit(properties),
                validatedDailyLimit(properties),
                frozenSafeLimits(),
                true);
    }

    TushareEndpointRateLimitPolicy(
            int globalSafeLimitPerMinute,
            int dailySafeLimitPerEndpoint,
            Map<Endpoint, Integer> endpointSafeLimitsPerMinute
    ) {
        this(
                globalSafeLimitPerMinute,
                dailySafeLimitPerEndpoint,
                endpointSafeLimitsPerMinute,
                false);
    }

    private TushareEndpointRateLimitPolicy(
            int globalSafeLimitPerMinute,
            int dailySafeLimitPerEndpoint,
            Map<Endpoint, Integer> endpointSafeLimitsPerMinute,
            boolean frozenF1cPolicy
    ) {
        if (globalSafeLimitPerMinute <= 0
                || dailySafeLimitPerEndpoint <= 0) {
            throw invalidPolicy();
        }
        Objects.requireNonNull(
                endpointSafeLimitsPerMinute,
                "endpointSafeLimitsPerMinute");
        if (!endpointSafeLimitsPerMinute.keySet().equals(
                java.util.EnumSet.allOf(Endpoint.class))) {
            throw invalidPolicy();
        }
        Map<Endpoint, Integer> copied = new EnumMap<>(Endpoint.class);
        endpointSafeLimitsPerMinute.forEach((endpoint, limit) -> {
            if (endpoint == null || limit == null || limit <= 0
                    || limit > globalSafeLimitPerMinute) {
                throw invalidPolicy();
            }
            copied.put(endpoint, limit);
        });
        if (frozenF1cPolicy
                && (globalSafeLimitPerMinute
                != GLOBAL_SAFE_LIMIT_PER_MINUTE
                || dailySafeLimitPerEndpoint
                != DAILY_SAFE_LIMIT_PER_ENDPOINT
                || !copied.equals(frozenSafeLimits()))) {
            throw invalidPolicy();
        }
        this.globalSafeLimitPerMinute = globalSafeLimitPerMinute;
        this.dailySafeLimitPerEndpoint = dailySafeLimitPerEndpoint;
        this.endpointSafeLimitsPerMinute = Map.copyOf(copied);
        this.frozenF1cPolicy = frozenF1cPolicy;
    }

    public static TushareEndpointRateLimitPolicy frozenF1cPolicy() {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        return new TushareEndpointRateLimitPolicy(properties);
    }

    public int globalSafeLimitPerMinute() {
        return globalSafeLimitPerMinute;
    }

    public int dailySafeLimitPerEndpoint() {
        return dailySafeLimitPerEndpoint;
    }

    public int safeLimitPerMinute(Endpoint endpoint) {
        Integer value = endpointSafeLimitsPerMinute.get(
                Objects.requireNonNull(endpoint, "endpoint"));
        if (value == null) {
            throw invalidPolicy();
        }
        return value;
    }

    public Optional<Endpoint> endpoint(String providerName) {
        return Endpoint.fromProviderName(providerName);
    }

    public Map<String, Integer> endpointSafeLimitsPerMinute() {
        Map<String, Integer> result = new LinkedHashMap<>();
        Endpoint.stream().forEach(endpoint -> result.put(
                endpoint.providerName(),
                safeLimitPerMinute(endpoint)));
        return Map.copyOf(result);
    }

    public boolean conservativeMinimumPolicyEnforced() {
        if (!frozenF1cPolicy) {
            return false;
        }
        return Endpoint.stream().allMatch(endpoint ->
                endpoint.effectiveOfficialLimitPerMinute()
                        == Math.min(
                        TushareTechnicalQualification
                                .GENERAL_2000_POINT_RATE_LIMIT_PER_MINUTE,
                        endpoint.endpointPageOfficialLimitPerMinute())
                        && safeLimitPerMinute(endpoint)
                        == endpoint.frozenApplicationSafeLimitPerMinute());
    }

    private static int validatedGlobalLimit(
            TushareMarketFactProperties properties
    ) {
        Objects.requireNonNull(properties, "properties")
                .validateFrozenContract();
        return properties.getApplicationSafeLimitPerMinute();
    }

    private static int validatedDailyLimit(
            TushareMarketFactProperties properties
    ) {
        Objects.requireNonNull(properties, "properties")
                .validateFrozenContract();
        return properties.getApplicationDailySafeLimitPerApi();
    }

    private static Map<Endpoint, Integer> frozenSafeLimits() {
        Map<Endpoint, Integer> values = new EnumMap<>(Endpoint.class);
        Endpoint.stream().forEach(endpoint -> values.put(
                endpoint,
                endpoint.frozenApplicationSafeLimitPerMinute()));
        return Map.copyOf(values);
    }

    private static IllegalArgumentException invalidPolicy() {
        return new IllegalArgumentException(
                "TUSHARE_ENDPOINT_RATE_POLICY_INVALID");
    }

    public enum Endpoint {
        STOCK_BASIC("stock_basic", 50, 45),
        DAILY("daily", 500, 180),
        ADJ_FACTOR("adj_factor", 200, 180),
        TRADE_CAL("trade_cal", 200, 180),
        DIVIDEND("dividend", 200, 180);

        private final String providerName;
        private final int endpointPageOfficialLimitPerMinute;
        private final int frozenApplicationSafeLimitPerMinute;

        Endpoint(
                String providerName,
                int endpointPageOfficialLimitPerMinute,
                int frozenApplicationSafeLimitPerMinute
        ) {
            this.providerName = providerName;
            this.endpointPageOfficialLimitPerMinute =
                    endpointPageOfficialLimitPerMinute;
            this.frozenApplicationSafeLimitPerMinute =
                    frozenApplicationSafeLimitPerMinute;
        }

        public String providerName() {
            return providerName;
        }

        public int endpointPageOfficialLimitPerMinute() {
            return endpointPageOfficialLimitPerMinute;
        }

        public int effectiveOfficialLimitPerMinute() {
            return Math.min(
                    TushareTechnicalQualification
                            .GENERAL_2000_POINT_RATE_LIMIT_PER_MINUTE,
                    endpointPageOfficialLimitPerMinute);
        }

        public int frozenApplicationSafeLimitPerMinute() {
            return frozenApplicationSafeLimitPerMinute;
        }

        private static Optional<Endpoint> fromProviderName(String value) {
            if (value == null) {
                return Optional.empty();
            }
            return stream()
                    .filter(endpoint -> endpoint.providerName.equals(value))
                    .findFirst();
        }

        static java.util.stream.Stream<Endpoint> stream() {
            return java.util.Arrays.stream(values());
        }
    }
}
