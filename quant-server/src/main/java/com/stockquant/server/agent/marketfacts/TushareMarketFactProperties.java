package com.stockquant.server.agent.marketfacts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Explicitly gated Tushare settings for the limited personal-research adapter.
 *
 * <p>The token is held only in memory for an outbound request. This class
 * deliberately does not override {@code toString()}.</p>
 */
@ConfigurationProperties(prefix = "stockquant.market-facts.tushare")
public class TushareMarketFactProperties {

    public static final int FROZEN_OFFICIAL_RATE_LIMIT_PER_MINUTE = 200;
    public static final int DEFAULT_APPLICATION_SAFE_LIMIT_PER_MINUTE = 180;
    public static final int DEFAULT_MAXIMUM_RATE_LIMIT_RETRIES = 2;
    public static final String OFFICIAL_API_HOST = "api.tushare.pro";

    private boolean enabled;
    private String baseUrl = "https://" + OFFICIAL_API_HOST;
    private String token;
    private int officialRateLimitPerMinute =
            FROZEN_OFFICIAL_RATE_LIMIT_PER_MINUTE;
    private int applicationSafeLimitPerMinute =
            DEFAULT_APPLICATION_SAFE_LIMIT_PER_MINUTE;
    private int maximumRateLimitRetries =
            DEFAULT_MAXIMUM_RATE_LIMIT_RETRIES;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration retryBackoff = Duration.ofSeconds(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getOfficialRateLimitPerMinute() {
        return officialRateLimitPerMinute;
    }

    public void setOfficialRateLimitPerMinute(
            int officialRateLimitPerMinute
    ) {
        this.officialRateLimitPerMinute = officialRateLimitPerMinute;
    }

    public int getApplicationSafeLimitPerMinute() {
        return applicationSafeLimitPerMinute;
    }

    public void setApplicationSafeLimitPerMinute(
            int applicationSafeLimitPerMinute
    ) {
        this.applicationSafeLimitPerMinute =
                applicationSafeLimitPerMinute;
    }

    public int getMaximumRateLimitRetries() {
        return maximumRateLimitRetries;
    }

    public void setMaximumRateLimitRetries(int maximumRateLimitRetries) {
        this.maximumRateLimitRetries = maximumRateLimitRetries;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public boolean tokenPresent() {
        return token != null && !token.isBlank();
    }

    String requireToken() {
        if (!enabled) {
            throw new IllegalStateException(
                    "TUSHARE_PROVIDER_DISABLED");
        }
        if (!tokenPresent()) {
            throw new IllegalStateException(
                    "TUSHARE_TOKEN_NOT_CONFIGURED");
        }
        return token;
    }

    URI validatedBaseUri() {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "invalid Tushare base URL", error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !OFFICIAL_API_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Tushare base URL must use the official HTTPS host");
        }
        return uri;
    }

    void validateRateLimits() {
        if (officialRateLimitPerMinute
                != FROZEN_OFFICIAL_RATE_LIMIT_PER_MINUTE) {
            throw new IllegalArgumentException(
                    "Tushare official rate limit must remain 200/minute");
        }
        if (applicationSafeLimitPerMinute <= 0
                || applicationSafeLimitPerMinute
                > officialRateLimitPerMinute) {
            throw new IllegalArgumentException(
                    "invalid Tushare application safe rate limit");
        }
        if (maximumRateLimitRetries < 0
                || maximumRateLimitRetries > 2) {
            throw new IllegalArgumentException(
                    "Tushare rate-limit retries must be between 0 and 2");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requireNonNegative(retryBackoff, "retryBackoff");
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "invalid Tushare " + field);
        }
    }

    private static void requireNonNegative(Duration value, String field) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(
                    "invalid Tushare " + field);
        }
    }
}
