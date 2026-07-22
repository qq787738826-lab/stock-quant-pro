package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class IngestionValidation {

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> EXCHANGES = Set.of("SSE", "SZSE");

    private IngestionValidation() {}

    static long positiveId(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static String text(String value, String name, int maxLength) {
        String normalized = Objects.requireNonNull(value, name + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    static String optionalText(String value, String name, int maxLength) {
        return value == null ? null : text(value, name, maxLength);
    }

    static String exchange(String value) {
        String normalized = text(value, "exchange", 16);
        if (!EXCHANGES.contains(normalized)) {
            throw new IllegalArgumentException("exchange must be SSE or SZSE");
        }
        return normalized;
    }

    static String sha256(String value, String name) {
        String normalized = text(value, name, 64);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex value");
        }
        return normalized;
    }

    static String optionalSha256(String value, String name) {
        return value == null ? null : sha256(value, name);
    }

    static Instant instant(Instant value, String name) {
        return required(value, name).truncatedTo(ChronoUnit.MICROS);
    }

    static Instant optionalInstant(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    static JsonNode object(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return value.deepCopy();
    }

    static JsonNode emptyObject(JsonNode value, String name) {
        JsonNode object = object(value, name);
        if (!object.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must be empty in the source-neutral ingestion stage");
        }
        return object;
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static void notBefore(Instant later, Instant earlier, String laterName, String earlierName) {
        if (earlier != null && later.isBefore(earlier)) {
            throw new IllegalArgumentException(laterName + " must not be before " + earlierName);
        }
    }

    static void effectiveTimes(LocalDate date, Instant instant) {
        if (date != null && instant != null
                && !date.equals(instant.atZone(SHANGHAI).toLocalDate())) {
            throw new IllegalArgumentException(
                    "sourceEffectiveDate must match sourceEffectiveAt in Asia/Shanghai");
        }
    }

    static void closedDateRange(LocalDate start, LocalDate end, String name) {
        required(start, name + "Start");
        required(end, name + "End");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(name + "End must not be before " + name + "Start");
        }
    }

    static void dateWithin(LocalDate value, LocalDate start, LocalDate end, String name) {
        required(value, name);
        closedDateRange(start, end, "range");
        if (value.isBefore(start) || value.isAfter(end)) {
            throw new IllegalArgumentException(name + " must be within the dataset range");
        }
    }
}
