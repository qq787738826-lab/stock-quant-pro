package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

final class TemporalValidation {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private TemporalValidation() {}

    static long positiveId(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static Long optionalPositiveId(Long value, String name) {
        if (value != null) {
            positiveId(value, name);
        }
        return value;
    }

    static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    static String symbol(String value) {
        String normalized = requiredText(value, "symbol");
        if (normalized.length() > 12) {
            throw new IllegalArgumentException("symbol must not exceed 12 characters");
        }
        return normalized;
    }

    static String sha256(String value, String name) {
        String normalized = requiredText(value, name);
        if (!LOWERCASE_SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex value");
        }
        return normalized;
    }

    static JsonNode object(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return value.deepCopy();
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static Instant instant(Instant value, String name) {
        return required(value, name).truncatedTo(ChronoUnit.MICROS);
    }

    static Instant optionalInstant(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    static void closedDateRange(LocalDate from, LocalDate to, String name) {
        required(from, name + " start");
        required(to, name + " end");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException(name + " end must be on or after start");
        }
    }

    static void halfOpenDateRange(LocalDate from, LocalDate to, String name) {
        required(from, name + " from");
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException(name + " to must be after from");
        }
    }

    static void halfOpenInstantRange(Instant from, Instant to, String name) {
        required(from, name + " from");
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException(name + " to must be after from");
        }
    }

    static void notBefore(Instant later, Instant earlier, String laterName, String earlierName) {
        if (earlier != null && later.isBefore(earlier)) {
            throw new IllegalArgumentException(laterName + " must not be before " + earlierName);
        }
    }

    static void session(
            boolean open,
            TradingSessionType type,
            Instant opensAt,
            Instant closesAt
    ) {
        required(type, "sessionType");
        if (open) {
            if (type != TradingSessionType.REGULAR && type != TradingSessionType.HALF_DAY) {
                throw new IllegalArgumentException("open sessions must be REGULAR or HALF_DAY");
            }
            required(opensAt, "sessionOpenAt");
            required(closesAt, "sessionCloseAt");
            if (!closesAt.isAfter(opensAt)) {
                throw new IllegalArgumentException("sessionCloseAt must be after sessionOpenAt");
            }
        } else {
            if (type != TradingSessionType.HOLIDAY
                    && type != TradingSessionType.TEMPORARY_CLOSURE) {
                throw new IllegalArgumentException(
                        "closed sessions must be HOLIDAY or TEMPORARY_CLOSURE");
            }
            if (opensAt != null || closesAt != null) {
                throw new IllegalArgumentException("closed sessions must not have session times");
            }
        }
    }

    static void adjacentOpenDates(
            LocalDate tradeDate,
            LocalDate previousOpenDate,
            LocalDate nextOpenDate
    ) {
        required(tradeDate, "tradeDate");
        if (previousOpenDate != null && !previousOpenDate.isBefore(tradeDate)) {
            throw new IllegalArgumentException("previousOpenDate must be before tradeDate");
        }
        if (nextOpenDate != null && !nextOpenDate.isAfter(tradeDate)) {
            throw new IllegalArgumentException("nextOpenDate must be after tradeDate");
        }
    }
}
