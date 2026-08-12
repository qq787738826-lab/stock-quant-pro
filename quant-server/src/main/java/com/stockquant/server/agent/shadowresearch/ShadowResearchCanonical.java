package com.stockquant.server.agent.shadowresearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;

final class ShadowResearchCanonical {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ShadowResearchCanonical() {
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("M4_CANONICAL_JSON_FAILED", error);
        }
    }

    static String hash(Object value) {
        return sha256(value instanceof String text ? text : json(value));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("M4_SHA256_UNAVAILABLE", error);
        }
    }

    static String runKey(
            LocalDate tradeDate,
            String slot,
            String strategyVersion
    ) {
        String identity = tradeDate + "|" + slot + "|" + strategyVersion;
        return "SHADOW_" + tradeDate.toString().replace("-", "") + "_"
                + slot + "_" + sha256(identity).substring(0, 16);
    }

    static Instant micros(Instant value) {
        long micros = value.getEpochSecond() * 1_000_000L
                + value.getNano() / 1_000L;
        return Instant.ofEpochSecond(micros / 1_000_000L,
                micros % 1_000_000L * 1_000L);
    }
}
