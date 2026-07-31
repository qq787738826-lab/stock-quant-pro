package com.stockquant.server.agent.marketfacts;

import java.util.Map;
import java.util.Objects;

/**
 * Typed proof that an F1C gateway is bound to the real endpoint-aware
 * in-process rate limiter.
 */
public interface F1cRateLimitedGateway {

    F1cRateLimitedGatewayContract f1cRateLimitContract();

    record F1cRateLimitedGatewayContract(
            boolean endpointSpecificRateLimitEnforced,
            boolean conservativeMinimumPolicyEnforced,
            int globalSafeLimitPerMinute,
            Map<String, Integer> endpointSafeLimitsPerMinute,
            int dailySafeLimitPerEndpoint,
            boolean unknownEndpointRejected,
            boolean distributedCoordination,
            long totalRateLimitedCallCount,
            Map<String, Long> endpointRateLimitedCallCounts
    ) {
        private static final Map<String, Integer> FROZEN_ENDPOINT_LIMITS =
                Map.of(
                        "stock_basic", 45,
                        "daily", 180,
                        "adj_factor", 180,
                        "trade_cal", 180,
                        "dividend", 180);

        public F1cRateLimitedGatewayContract {
            endpointSafeLimitsPerMinute = Map.copyOf(
                    Objects.requireNonNull(
                            endpointSafeLimitsPerMinute,
                            "endpointSafeLimitsPerMinute"));
            endpointRateLimitedCallCounts = Map.copyOf(
                    Objects.requireNonNull(
                            endpointRateLimitedCallCounts,
                            "endpointRateLimitedCallCounts"));
            if (totalRateLimitedCallCount < 0
                    || endpointRateLimitedCallCounts.values().stream()
                    .anyMatch(value -> value == null || value < 0)) {
                throw invalid();
            }
        }

        static F1cRateLimitedGatewayContract from(
                TushareEndpointRateLimitPolicy policy,
                TushareTokenRateLimiter limiter
        ) {
            Objects.requireNonNull(policy, "policy");
            TushareTokenRateLimiter.RateLimitSnapshot snapshot =
                    Objects.requireNonNull(limiter, "limiter").snapshot();
            Map<String, Integer> policyLimits =
                    policy.endpointSafeLimitsPerMinute();
            return new F1cRateLimitedGatewayContract(
                    snapshot.endpointSafeLimitsPerMinute()
                            .equals(policyLimits),
                    policy.conservativeMinimumPolicyEnforced(),
                    snapshot.globalSafeLimitPerMinute(),
                    snapshot.endpointSafeLimitsPerMinute(),
                    snapshot.dailySafeLimitPerApi(),
                    policy.endpoint("unknown").isEmpty(),
                    snapshot.distributedCoordination(),
                    snapshot.totalCallCount(),
                    snapshot.endpointCallCounts());
        }

        public void validateFrozenF1c() {
            if (!endpointSpecificRateLimitEnforced
                    || !conservativeMinimumPolicyEnforced
                    || globalSafeLimitPerMinute != 180
                    || !endpointSafeLimitsPerMinute.equals(
                    FROZEN_ENDPOINT_LIMITS)
                    || dailySafeLimitPerEndpoint != 90_000
                    || !unknownEndpointRejected
                    || distributedCoordination) {
                throw invalid();
            }
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "TUSHARE_REDUCED_RUNTIME_GATEWAY_RATE_LIMIT_CONTRACT_INVALID");
        }
    }
}
