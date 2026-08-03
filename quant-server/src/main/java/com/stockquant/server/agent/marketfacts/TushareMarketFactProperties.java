package com.stockquant.server.agent.marketfacts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/**
 * Explicitly gated Tushare settings for the limited personal-research adapter.
 *
 * <p>The token is held only in memory for an outbound request. This class
 * deliberately does not override {@code toString()}.</p>
 */
@ConfigurationProperties(prefix = "stockquant.market-facts.tushare")
public class TushareMarketFactProperties {

    public static final int FROZEN_OFFICIAL_RATE_LIMIT_PER_MINUTE = 200;
    public static final int FROZEN_OFFICIAL_DAILY_LIMIT_PER_API = 100_000;
    public static final int DEFAULT_APPLICATION_SAFE_LIMIT_PER_MINUTE = 180;
    public static final int DEFAULT_APPLICATION_DAILY_SAFE_LIMIT_PER_API =
            90_000;
    public static final int DEFAULT_MAXIMUM_RATE_LIMIT_RETRIES = 2;
    public static final String OFFICIAL_API_HOST = "api.tushare.pro";

    private Mode mode = Mode.DISABLED;
    private String baseUrl = "https://" + OFFICIAL_API_HOST;
    private char[] token;
    private int officialRateLimitPerMinute =
            FROZEN_OFFICIAL_RATE_LIMIT_PER_MINUTE;
    private int officialDailyLimitPerApi =
            FROZEN_OFFICIAL_DAILY_LIMIT_PER_API;
    private int applicationSafeLimitPerMinute =
            DEFAULT_APPLICATION_SAFE_LIMIT_PER_MINUTE;
    private int applicationDailySafeLimitPerApi =
            DEFAULT_APPLICATION_DAILY_SAFE_LIMIT_PER_API;
    private int maximumRateLimitRetries =
            DEFAULT_MAXIMUM_RATE_LIMIT_RETRIES;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration retryBackoff = Duration.ofSeconds(1);

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token == null ? null : new String(token);
    }

    public void setToken(String token) {
        clearToken();
        this.token = token == null ? null : token.toCharArray();
    }

    void setToken(char[] token) {
        clearToken();
        this.token = token == null ? null : token.clone();
    }

    void clearToken() {
        if (token != null) {
            Arrays.fill(token, '\0');
            token = null;
        }
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

    public int getOfficialDailyLimitPerApi() {
        return officialDailyLimitPerApi;
    }

    public void setOfficialDailyLimitPerApi(
            int officialDailyLimitPerApi
    ) {
        this.officialDailyLimitPerApi = officialDailyLimitPerApi;
    }

    public int getApplicationDailySafeLimitPerApi() {
        return applicationDailySafeLimitPerApi;
    }

    public void setApplicationDailySafeLimitPerApi(
            int applicationDailySafeLimitPerApi
    ) {
        this.applicationDailySafeLimitPerApi =
                applicationDailySafeLimitPerApi;
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
        if (token == null || token.length == 0) {
            return false;
        }
        for (char value : token) {
            if (!Character.isWhitespace(value)) {
                return true;
            }
        }
        return false;
    }

    String requireManualBoundedToken() {
        if (mode != Mode.MANUAL_BOUNDED) {
            throw new IllegalStateException(
                    "TUSHARE_PROVIDER_DISABLED");
        }
        if (!tokenPresent()) {
            throw new IllegalStateException(
                    "TUSHARE_TOKEN_NOT_CONFIGURED");
        }
        return new String(token);
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

    void validateFrozenContract() {
        if (mode == null) {
            throw new IllegalArgumentException(
                    "Tushare mode is required");
        }
        if (officialRateLimitPerMinute
                != FROZEN_OFFICIAL_RATE_LIMIT_PER_MINUTE) {
            throw new IllegalArgumentException(
                    "Tushare official rate limit must remain 200/minute");
        }
        if (officialDailyLimitPerApi
                != FROZEN_OFFICIAL_DAILY_LIMIT_PER_API) {
            throw new IllegalArgumentException(
                    "Tushare official daily limit must remain 100000/API");
        }
        if (applicationSafeLimitPerMinute
                != DEFAULT_APPLICATION_SAFE_LIMIT_PER_MINUTE) {
            throw new IllegalArgumentException(
                    "Tushare application safe rate must remain 180/minute");
        }
        if (applicationDailySafeLimitPerApi
                != DEFAULT_APPLICATION_DAILY_SAFE_LIMIT_PER_API) {
            throw new IllegalArgumentException(
                    "Tushare application daily safe limit must remain "
                            + "90000/API");
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

    public enum Mode {
        DISABLED,
        MANUAL_BOUNDED
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
