package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class TemporalJdbcSupport {

    private TemporalJdbcSupport() {}

    static String writeJson(ObjectMapper mapper, JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("temporal JSON cannot be serialized", error);
        }
    }

    static JsonNode readJson(ObjectMapper mapper, String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("temporal JSON stored in the database is invalid", error);
        }
    }

    static OffsetDateTime timestamptz(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return (Instant) value;
    }
}
